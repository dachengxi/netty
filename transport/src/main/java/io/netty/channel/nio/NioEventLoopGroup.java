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
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;

import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * {@link MultithreadEventLoopGroup} implementations which is used for NIO {@link Selector} based {@link Channel}s.
 *
 */
public class NioEventLoopGroup extends MultithreadEventLoopGroup {

    /*
        NioEventLoopGroup可认为是一个带有Schedule类型的线程池。

        在使用的时候一般是如下的方式：
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup(X);
        一般在实例化boss这个group的时候，会指定线程数这个参数为1，worker的线程数参数根据实际情况填写。
        如果没有指定线程数参数，默认是NettyRuntime.availableProcessors() * 2。

        boss对应Reactor的主线程，用来处理连接事件，worker对应Reactor的子线程，用来处理读写事件。

        NioEventLoopGroup在实例化的时候主要工作是，使用指定的线程数来实例化对应的NioEventLoop，
        也即是在一个NioEventLoopGroup内部包含了N个NioEventLoop。在NioEventLoop实例化的时候，
        会进行Java NIO的Selector的open操作，也就是每个NioEventLoop会和一个Selector进行绑定。

        Boss EventLoopGroup和Worker EventLoopGroup不太一样，每个Boss EventLoopGroup中只包含一个
        EventLoop，这个EventLoop对应一个Selector和一个TaskQueue，Boss的EventLoop会将连接的
        Channel注册到Worker的EventLoop上。

        Worker EventLoopGroup中会包含多个EventLoop，每个EventLoop对应一个Selector和TaskQueue，
        Worker的EventLoop用来执行ChannelPipeline。

        在使用Java NIO的时候，通常会使用以下的方式：
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(new InetSocketAddress("localhost", 9001));

        selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            selector.select();
            // ...
        }

        NioEventLoopGroup在实例化的时候就相当于执行了selector = Selector.open()这一步。

        NioEventLoopGroup的相关字段：

        - EventExecutor[] children：用来存放EventLoopGroup对应的EventLoop
        - EventExecutorChooserFactory.EventExecutorChooser chooser：Boss和Worker EventLoopGroup中都有可能有多个EventLoop，chooser用来选择使用哪个EventLoop来处理请求
        - AtomicInteger terminatedChildren = new AtomicInteger()：已经停止工作的EventLoop的个数

        NioEventLoop的相关字段：

        - SelectorProvider provider：用来选择具体的Selector的实现，默认是SelectorPorvier.provider()
        - SelectStrategy selectStrategy：用来控制select循环的行为，默认是DefaultSelectStrategy
        - Selector selector：Selector，可能是经过优化后包装的SelectedSelectionKeySetSelector
        - Selector unwrappedSelector：原始的未经包装的Selector
        - Queue<Runnable> taskQueue：用于存储任务的队列
        - Queue<Runnable> tailTasks：用于存储EventLoop事件循环结束后需要执行的任务
        - Executor executor：如果NioEventLoopGroup不指定的话，默认是ThreadPerTaskExecutor
        - RejectedExecutionHandler rejectedExecutionHandler：拒绝策略，默认是RejectedExecutionHandlers.reject()
        - EventExecutorGroup parent：当前EventLoop所属的EventLoopGroup

        Netty4的线程模型有变化，提交到EventLoop的任务会被线程无锁串行化执行，EventLoop的任务队列的的使用场景就变成了：
        多个生产者，单个消费者。使用的是MPSC无锁队列实现任务队列。
     */

    /**
     * Create a new instance using the default number of threads, the default {@link ThreadFactory} and
     * the {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup() {
        this(0);
    }

    /**
     * Create a new instance using the specified number of threads, {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads) {
        this(nThreads, (Executor) null);
    }

    /**
     * Create a new instance using the default number of threads, the given {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(ThreadFactory threadFactory) {
        this(0, threadFactory, SelectorProvider.provider());
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the
     * {@link SelectorProvider} which is returned by {@link SelectorProvider#provider()}.
     */
    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory) {
        this(nThreads, threadFactory, SelectorProvider.provider());
    }

    public NioEventLoopGroup(int nThreads, Executor executor) {
        this(nThreads, executor, SelectorProvider.provider());
    }

    /**
     * Create a new instance using the specified number of threads, the given {@link ThreadFactory} and the given
     * {@link SelectorProvider}.
     */
    public NioEventLoopGroup(
            int nThreads, ThreadFactory threadFactory, final SelectorProvider selectorProvider) {
        this(nThreads, threadFactory, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads, ThreadFactory threadFactory,
        final SelectorProvider selectorProvider, final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, threadFactory, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(
            int nThreads, Executor executor, final SelectorProvider selectorProvider) {
        this(nThreads, executor, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
    }

    public NioEventLoopGroup(int nThreads, Executor executor, final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, executor, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory,
                RejectedExecutionHandlers.reject());
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory,
                             final RejectedExecutionHandler rejectedExecutionHandler) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler);
    }

    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             final SelectorProvider selectorProvider,
                             final SelectStrategyFactory selectStrategyFactory,
                             final RejectedExecutionHandler rejectedExecutionHandler,
                             final EventLoopTaskQueueFactory taskQueueFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory,
                rejectedExecutionHandler, taskQueueFactory);
    }

    /**
     * @param nThreads the number of threads that will be used by this instance.
     * @param executor the Executor to use, or {@code null} if default one should be used.
     * @param chooserFactory the {@link EventExecutorChooserFactory} to use.
     * @param selectorProvider the {@link SelectorProvider} to use.
     * @param selectStrategyFactory the {@link SelectStrategyFactory} to use.
     * @param rejectedExecutionHandler the {@link RejectedExecutionHandler} to use.
     * @param taskQueueFactory the {@link EventLoopTaskQueueFactory} to use for
     *                         {@link SingleThreadEventLoop#execute(Runnable)},
     *                         or {@code null} if default one should be used.
     * @param tailTaskQueueFactory the {@link EventLoopTaskQueueFactory} to use for
     *                             {@link SingleThreadEventLoop#executeAfterEventLoopIteration(Runnable)},
     *                             or {@code null} if default one should be used.
     */
    public NioEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                             SelectorProvider selectorProvider,
                             SelectStrategyFactory selectStrategyFactory,
                             RejectedExecutionHandler rejectedExecutionHandler,
                             EventLoopTaskQueueFactory taskQueueFactory,
                             EventLoopTaskQueueFactory tailTaskQueueFactory) {
        super(nThreads, executor, chooserFactory, selectorProvider, selectStrategyFactory,
                rejectedExecutionHandler, taskQueueFactory, tailTaskQueueFactory);
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the child event loops.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).setIoRatio(ioRatio);
        }
    }

    /**
     * Replaces the current {@link Selector}s of the child event loops with newly created {@link Selector}s to work
     * around the  infamous epoll 100% CPU bug.
     */
    public void rebuildSelectors() {
        for (EventExecutor e: this) {
            ((NioEventLoop) e).rebuildSelector();
        }
    }

    /**
     * NioEventLoopGroup用来创建NioEventLoop的方法
     * @param executor，如果NioEventLoopGroup不指定的话，默认是ThreadPerTaskExecutor
     * @param args
     * @return
     * @throws Exception
     */
    @Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception {
        // 用来选择具体的Selector的实现
        SelectorProvider selectorProvider = (SelectorProvider) args[0];
        // 用来控制select循环的行为
        SelectStrategyFactory selectStrategyFactory = (SelectStrategyFactory) args[1];
        // 拒绝策略，默认是RejectedExecutionHandlers.reject()
        RejectedExecutionHandler rejectedExecutionHandler = (RejectedExecutionHandler) args[2];
        EventLoopTaskQueueFactory taskQueueFactory = null;
        EventLoopTaskQueueFactory tailTaskQueueFactory = null;

        int argsLength = args.length;
        if (argsLength > 3) {
            taskQueueFactory = (EventLoopTaskQueueFactory) args[3];
        }
        if (argsLength > 4) {
            tailTaskQueueFactory = (EventLoopTaskQueueFactory) args[4];
        }
        // 实例化一个NioEventLoop对象
        return new NioEventLoop(this, executor, selectorProvider,
                selectStrategyFactory.newSelectStrategy(),
                rejectedExecutionHandler, taskQueueFactory, tailTaskQueueFactory);
    }
}
