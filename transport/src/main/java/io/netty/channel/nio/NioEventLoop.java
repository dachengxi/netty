/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * NioEventLoop和一个线程一一对应
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否需要禁用对sun.nio.ch.SelectorImpl中的selectedKeys的优化
     * 默认是不禁用的，也就是需要进行优化
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * 可能是经过优化后包装的SelectedSelectionKeySetSelector
     */
    private Selector selector;

    /**
     * 原始的未经包装的Selector
     */
    private Selector unwrappedSelector;

    /**
     * selectedKeys是Netty优化SelectorImpl的selectedKeys和publicSelectedKeys字段的替代者，
     * selectedKeys中保存了selectedKeys和publicSelectedKeys字段的值
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * 用来选择具体的Selector的实现
     */
    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    /**
     * 用来控制select循环的行为，默认是DefaultSelectStrategy
     */
    private final SelectStrategy selectStrategy;

    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    /**
     * 构造NioEventloop对象
     * @param parent
     * @param executor 如果NioEventLoopGroup不指定的话，默认是ThreadPerTaskExecutor
     * @param selectorProvider 用来选择具体的Selector的实现，默认是SelectorPorvier.provider()
     * @param strategy 用来控制select循环的行为，默认是DefaultSelectStrategy
     * @param rejectedExecutionHandler 拒绝策略，默认是RejectedExecutionHandlers.reject()
     * @param taskQueueFactory
     * @param tailTaskQueueFactory
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        /*
            newTaskQueue中使用的是MPSC无锁队列

            Mpsc无锁队列
            M：Multiple，多个的
            P：Producer，生产者
            S：Single，单个的
            C：Consumer，消费者
            MPSC队列是一个适用于多个生产者单个消费者的高性能无锁队列。

            Netty4的线程模型决定了taskQueue的生产者可以有多个，但是taskQueue的消费者
            只有它所在的EventLoop中的线程来消费，而一个EventLoop中只对应一个线程，所以
            消费者是单线程的。

            MPSC使用无锁方式保证多个生产者访问队列的安全性。
         */
        // TODO MPSC队列实现未看
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);
        // 用来选择具体的Selector的实现
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        // 用来控制select循环的行为
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 打开一个Selector，Java NIO中经常使用方式：selector = Selector.open()，并尝试对Selector进行优化
        final SelectorTuple selectorTuple = openSelector();
        // 如果对原生Selector进行了优化，这里是包装后的Selector
        this.selector = selectorTuple.selector;
        // 原始的未经包装的Selector
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        // 原始的未经包装的Selector
        final Selector unwrappedSelector;
        // 如果对原生Selector进行了优化，则这里是包装后的SelectedSelectionKeySetSelector，
        // 如果没有进行优化，则这里是原始的未经包装的Selector
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 打开一个Selector，Java NIO中经常使用方式：selector = Selector.open()，
     * 这一步会尝试对原生Selector进行优化
     * @return
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 原始的未经包装过的Selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 如果不需要对sun.nio.ch.SelectorImpl中的selectedKeys的优化，直接返回原生的Selector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 尝试获取sun.nio.ch.SelectorImpl的Class对象
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 判断Class对象是不是Selector的实现类
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            // 如果Class对象不是Selector的实现类或者是异常类，则返回原生的Selector
            return new SelectorTuple(unwrappedSelector);
        }

        // SelectorImpl的Class对象
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        /*
            Netty会对SelectorImpl进行优化，优化的内容是使用SelectedSelectionKeySet替换掉SelectorImpl的
            两个字段：
            - protected Set<SelectionKey> selectedKeys;
            - private Set<SelectionKey> publicSelectedKeys;
            在SelectorImpl中这两个Set都是HashSet，HastSet本身是基于HashMap实现的，HashMap的内部实现比较复杂，
            比如扩容、冲突解决、rehash、链表和红黑树的相互转换等等，Netty则使用自己实现的一个简单的Set来替换，
            实现类是：SelectedSelectionKeySet，内部使用一个数组进行存储，扩容也是直接将数据长度翻倍并复制数据到
            新数组中去。
         */
        // SelectedSelectionKeySet中会存储SelectorImpl中的selectedKeys和publicSelectedKeys两个Set的数据
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // SelectorImpl的selectedKeys字段
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    // SelectorImpl的publicSelectedKeys字段
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // JDK版本大于等于9并且Unsafe相关类存在
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            // 使用Unsafe的方式替换掉selectedKeys字段和publicSelectedKeys字段
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    // 下面是使用反射的方式替换掉selectedKeys字段和publicSelectedKeys字段
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            // 如果优化的时候发生了异常，则返回原生的Selector
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 返回一个原生Selector以及包装后的Selector
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /*
        主要做两件事情：
        - 监听IO事件
        - 处理taskQueue中的任务
     */
    @Override
    protected void run() {
        // 这个计数用来处理空轮询的BUG
        int selectCnt = 0;
        // 循环处理IO事件、taskQueue中的任务
        for (;;) {
            try {
                int strategy;
                try {
                    /*
                        如果taskQueue或者tailTask不为空，也就是队列中有任务，则使用策略：selectNowSupplier，
                        selectNowSupplier中会使用Selector.selectNow方法立刻查询是否有准备就绪的IO；
                        否则如果队列中没有任务则使用策略：SelectStrategy.SELECT，调用Selector.select方法
                        进行查询。

                        select和selectNow方法的区别：
                        - select，调用这个select方法时，会将上次select之后准备好的Channel对应的SelectionKey复制
                          到selected set中，如果没有任何Channel准备好，则select方法会阻塞。
                        - selectNow，这个方法调用后，如果没有任何Channel准备好，则会直接返回0

                        如果队列中有任务，则先使用selectNow()方法看有没有准备好的Channel，有的话就返回准备好的
                        Channel的个数，然后处理准保好的Channel；如果没有准备好的Channel就直接返回0，继续处理
                        队列中的任务。
                     */
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO
                        // NIO中没有这个策略

                    case SelectStrategy.SELECT:
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            // 队列中没有任务要处理，这里就会调用Selector.select()，如果没有准备好的Channel就阻塞在这里
                            if (!hasTasks()) {
                                // 这里会根据参数来具体调用select()、select(timeout)、selectNow()
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;
                /*
                    IO事件处理与执行队列中任务的比率，默认为50，表示IO事件处理和队列中任务的处理时间相等；
                    如果为100，则表示每次都是先执行IO事件，再进行队列中任务的处理。
                    设置这个比率是可以根据实际情况来调整具体的IO处理时间和任务处理时间。
                 */
                final int ioRatio = this.ioRatio;
                boolean ranTasks;
                // io比率为100，表示每次都是先执行IO事件，再处理队列中的任务
                if (ioRatio == 100) {
                    try {
                        if (strategy > 0) {
                            // 处理IO操作
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        // 处理队列中的任务
                        ranTasks = runAllTasks();
                    }
                }
                // IO比率不为100，有IO事件需要处理，则先处理IO事件，然后根据IO事件的时间和比率计算出任务的处理应该要执行多久
                else if (strategy > 0) {
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理IO操作
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 处理队列中的任务
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
                // IO比率不为100，没有IO事件要处理，则直接处理队列中的任务
                else {
                    // 处理队列中的任务
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                // 如果有任务处理了，或者是有IO事件处理，则说明没有空轮询，将selectCnt计数清零
                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    selectCnt = 0;
                }
                // 在没有任务处理也没有IO事件处理的时候，需要判断下selectCnt是否超过了限制范围，默认是512次
                // 如果超过了阈值，说明是空轮询BUG，就会重新创建一个Selector来替换原来的Selector
                else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        // selectedKeys不为null，说明是优化后的Selector
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            // 未经优化的Selector
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 已注册IO事件
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            // 对应的Channel
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                // 处理IO事件
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // unsafe是Netty内部使用的接口，在ServerSocketChannel实例化的时候生成
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            // 当前事件key的掩码
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 包含连接事件
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // 包含写事件
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // 包含读事件或者连接新建事件
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow();
    }

    private int select(long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) {
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
