/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * Chunk是Netty向操作系统申请内存的单位，Netty中内存的分配操作都是基于Chunk来完成。
 * Netty中每个Chunk（也就是PoolChunk）默认是16M。
 */
final class PoolChunk<T> implements PoolChunkMetric {

    /*
        内存碎片
        Linux中内存分配的最小单位是page，大小为4K。内存分配过程中会产生内存碎片：
        - 内部碎片，page内产生的碎片
        - 外部碎片，page外产生的碎片


        内存分配算法
        - 动态内存分配
        - 伙伴算法
        - Slab算法


        动态内存分配
        该方法对一整块内存按需进行分配，已分配的内存会记录元数据，空闲的内存会使用链表进行维护，
        查找空闲内存的策略常用的有如下几种方式：
        - 首次适应算法（first fit）
        - 循环首次适应算法（next fit）
        - 最佳适应算法（best fit）


        首次适应算法
        空闲分区链表是一个双向链表，空闲分区按照地址递增顺序排序，查找空闲分区的时候从链表头开始查找，
        直到找到第一个满足条件的分区进行分配。首次适应算法每次查找都从链表头开始，效率不高；分配后会
        产生很多小的空闲分区。


        循环首次适应算法
        循环首次适应算法每次查找的时候不从头开始，而是从上一次找到空闲分区的地方开始查找，相对于首次
        适应算法查找效率有所提高，但分配后会产生很多小的空闲分区。


        最佳适应算法
        最佳适应算法会将空闲内存链表从小到大进行排序，这样每次查找到的空闲分区就是最优的。最佳适应算
        法空间利用率更高，但是还是会产生很多小的空闲分区，并且每次分配后都需要重新排序，影响性能。


        伙伴算法
        伙伴算法将空闲内存分为11个链表，每个链表中都是包含X（X=1、2、4、8、16、32、64、128、256、
        512、1024）个连续的page，并且内存是连续的。比如第一个链表中所有的元素都是大小为1个page的
        连续内存，第二个链表中都是大小为2个page的连续内存。

        伙伴算法最小的分配粒度是page，会造成严重的内部碎片。伙伴算法释放内存的时候，会检查其伙伴块
        是否释放，如果两个伙伴块大小相同，地址连续，则将两块进行合并。


        Slab算法
        伙伴算法不适用于小内存的场景，Slab算法在伙伴算法的基础上增加了对小内存场景的分配优化以及内存
        对象缓存的优化。

        Slab算法中维护着大小不同的Slab集合，每个Slab集合中包含三个Slab链表：
        - slab_full：完全分配使用
        - slab_partial：部分分配使用
        - slab_empty：完全空闲
        每个Slab都是一个或者多个连续的Page，那个Slab可以存储多个对象，Slab算法会将类型相同的对象分为
        一类。释放后的内存不会丢弃掉，而是会缓存起来。


        jemalloc算法
        。。。


     */

    /*
        Netty将内存分成不同的规格：
        - tiny：0～512B之间的内存块
        - small：512B～8K之间的内存块
        - normal：8K～16M之间的内存块
        - huge：大于16M的内存块

        Netty中一个Chunk=16M，一个Page=8k，小于8k的统称为Subpage，Netty向操作系统申请内存是一Chunk为单位的，
        一个Chunk=16M，每个Chunk中包含多个Page，每个Page=8K。对于小内存的分配，远小于Page的大小的内存，使用
        Subpage进行分配。

        huge类型的内存块，直接分配Chunk；normal类型的内存块使用Chunk进行分配管理；small和tiny类型的内存块使用
        Subpage进行分配管理。

        Chunk管理normal类型的内存块时，使用一个满二叉树进行分配管理；Subpage管理tiny和small类型的内存块时使用，
        每个Subpage中管理同一大小的内存块，内部使用双向链表存放内存块。

     */

    /*
        PoolChunk用来管理内存的分配和回收，默认每个PoolChunk大小为16M。
        PoolChunk是Page的集合，Netty会将PoolChunk使用伙伴算法分成2048个Page，以满二叉树的形式组织。

        PoolChunk对应jemalloc 3.x的算法。
     */

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /**
     * 持有当前PoolChunk的Arena
     */
    final PoolArena<T> arena;

    /**
     * 存储的数据，如果是堆内存，则是byte[]；如果是直接内存，则是ByteBuffer
     */
    final T memory;

    /**
     * 当前PoolChunk是否使用池化管理，如果是Huge级别的内存，则unpooled始终为true
     */
    final boolean unpooled;

    /**
     * 内存对齐使用的偏移量
     */
    final int offset;

    /**
     * 记录满二叉树节点的分配信息，也就是树中的节点是否被分配，初始值和depthMap一样。
     * 数组大小为4096，memoryMap会随着节点的分配，不断变化。
     */
    private final byte[] memoryMap;

    /**
     * 存放满二叉树的节点对应的的高度，这里面的值不会变化，数组大小为4096。
     */
    private final byte[] depthMap;

    /**
     * PoolChunk管理的8K（Page）的内存块，数组大小为2048。
     *
     * 用来记录那些Page被转化为了Subpage
     */
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;

    /**
     * page的大小，8K
     */
    private final int pageSize;

    /**
     * page偏移量，默认13
     *
     * 一个Chunk大小为8K=8192=2^13，所以pageShifts=13
     */
    private final int pageShifts;

    /**
     * 满二叉树的高度11
     */
    private final int maxOrder;

    /**
     * Chunk大小，默认16M
     */
    private final int chunkSize;

    /**
     * chunkSize取2的对数，默认24
     */
    private final int log2ChunkSize;

    /**
     * PoolSubpage数组长度，默认2048
     */
    private final int maxSubpageAllocs;

    /**
     * Used to mark memory as unusable
     * 一个节点不可用的时候，会将对应memoryMap[id]设置为unusable，默认是12
     */
    private final byte unusable;

    /**
     * 剩余的内存大小
     */
    private int freeBytes;

    PoolChunkList<T> parent;

    /**
     * PoolChunk的前一个节点
     */
    PoolChunk<T> prev;

    /**
     * PoolChunk的后一个节点
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        // 大于等于8K的使用PoolChunk进行管理
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else {
            // 小于8K的使用PoolSubpage进行管理
            return allocateSubpage(normCapacity);
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     *
     * 从指定深度的节点中找出一个空闲的节点
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     * 分配大于等于8K的内存
     */
    private long allocateRun(int normCapacity) {
        // 根据要分配的内存大小，计算出二叉树对应的节点的深度，也就是该在哪一层分配
        // 假设normCapacity=8K，则d = 11 - (13 - 13) = 11
        int d = maxOrder - (log2(normCapacity) - pageShifts);

        // 从该深度的节点中找出一个空闲的节点，id是memoryMap数组的索引
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }

        // 已分配的内存大小要从空闲的大小中减去
        freeBytes -= runLength(id);

        // 返回memoryMap中对应的索引
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     *
     * 分配小于8K的内存
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 根据要分配的内存大小找到PoolArea中对应的Subpage数组的头节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        synchronized (head) {
            // 小于8K的内存一定在叶子结点，直接从二叉树最底层开始查找
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
            // 从二叉树中找到一个可用节点
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;

            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                // 创建PoolSubpage对象，会将内存切分为大小相同的自内存块，然后加入到PoolArena的双向链表中
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            // 分配内存
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);
    }

    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
