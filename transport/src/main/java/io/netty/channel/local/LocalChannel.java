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
package io.netty.channel.local;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.PlatformDependent;

import java.net.SocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.util.Queue;

/**
 * A {@link Channel} for the local transport.
 */
public class LocalChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final int MAX_READER_STACK_DEPTH = 8;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    // To futher optimize this we could write our own SPSC queue.
    private final Queue<Object> inboundBuffer = PlatformDependent.newMpscQueue();
    private final Runnable readTask = new Runnable() {
        @Override
        public void run() {
            ChannelPipeline pipeline = pipeline();
            for (;;) {
                Object m = inboundBuffer.poll();
                if (m == null) {
                    break;
                }
                pipeline.fireChannelRead(m);
            }
            pipeline.fireChannelReadComplete();
        }
    };
    private final Runnable shutdownHook = new Runnable() {
        @Override
        public void run() {
            unsafe().close(unsafe().voidPromise());
        }
    };

    private volatile int state; // 0 - open, 1 - bound, 2 - connected, 3 - closed
    private volatile LocalChannel peer;
    private volatile LocalAddress localAddress;
    private volatile LocalAddress remoteAddress;
    private volatile ChannelPromise connectPromise;
    private volatile boolean readInProgress;
    private volatile boolean registerInProgress;
    private volatile boolean writeInProgress;

    public LocalChannel() {
        super(null);
    }

    LocalChannel(LocalServerChannel parent, LocalChannel peer) {
        super(parent);
        this.peer = peer;
        localAddress = parent.localAddress();
        remoteAddress = peer.localAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public LocalServerChannel parent() {
        return (LocalServerChannel) super.parent();
    }

    @Override
    public LocalAddress localAddress() {
        return (LocalAddress) super.localAddress();
    }

    @Override
    public LocalAddress remoteAddress() {
        return (LocalAddress) super.remoteAddress();
    }

    @Override
    public boolean isOpen() {
        return state < 3;
    }

    @Override
    public boolean isActive() {
        return state == 2;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new LocalUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof SingleThreadEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doRegister() throws Exception {
        // Check if both peer and parent are non-null because this channel was created by a LocalServerChannel.
        // This is needed as a peer may not be null also if a LocalChannel was connected before and
        // deregistered / registered later again.
        //
        // See https://github.com/netty/netty/issues/2400
        if (peer != null && parent() != null) {
            // Store the peer in a local variable as it may be set to null if doClose() is called.
            // Because of this we also set registerInProgress to true as we check for this in doClose() and make sure
            // we delay the fireChannelInactive() to be fired after the fireChannelActive() and so keep the correct
            // order of events.
            //
            // See https://github.com/netty/netty/issues/2144
            final LocalChannel peer = this.peer;
            registerInProgress = true;
            state = 2;

            peer.remoteAddress = parent().localAddress();
            peer.state = 2;

            // Always call peer.eventLoop().execute() even if peer.eventLoop().inEventLoop() is true.
            // This ensures that if both channels are on the same event loop, the peer's channelActive
            // event is triggered *after* this channel's channelRegistered event, so that this channel's
            // pipeline is fully initialized by ChannelInitializer before any channelRead events.
            peer.eventLoop().execute(new OneTimeTask() {
                @Override
                public void run() {
                    registerInProgress = false;
                    peer.pipeline().fireChannelActive();
                    peer.connectPromise.setSuccess();
                }
            });
        }
        ((SingleThreadEventExecutor) eventLoop()).addShutdownHook(shutdownHook);
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        this.localAddress =
                LocalChannelRegistry.register(this, this.localAddress,
                        localAddress);
        state = 1;
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        final LocalChannel peer = this.peer;
        if (state <= 2) {
            // To preserve ordering of events we must process any pending reads
            if (writeInProgress && peer != null) {
                finishPeerRead(peer);
            }
            // Update all internal state before the closeFuture is notified.
            if (localAddress != null) {
                if (parent() == null) {
                    LocalChannelRegistry.unregister(localAddress);
                }
                localAddress = null;
            }
            state = 3;
        }

        if (peer != null && peer.isActive()) {
            // Need to execute the close in the correct EventLoop (see https://github.com/netty/netty/issues/1777).
            // Also check if the registration was not done yet. In this case we submit the close to the EventLoop
            // to make sure its run after the registration completes (see https://github.com/netty/netty/issues/2144).
            if (peer.eventLoop().inEventLoop() && !registerInProgress) {
                doPeerClose(peer, peer.writeInProgress);
            } else {
                // This value may change, and so we should save it before executing the Runnable.
                final boolean peerWriteInProgress = peer.writeInProgress;
                peer.eventLoop().execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        doPeerClose(peer, peerWriteInProgress);
                    }
                });
            }
            this.peer = null;
        }
    }

    private void doPeerClose(LocalChannel peer, boolean peerWriteInProgress) {
        if (peerWriteInProgress) {
            finishPeerRead0(this);
        }
        peer.unsafe().close(peer.unsafe().voidPromise());
    }

    @Override
    protected void doDeregister() throws Exception {
        // Just remove the shutdownHook as this Channel may be closed later or registered to another EventLoop
        ((SingleThreadEventExecutor) eventLoop()).removeShutdownHook(shutdownHook);
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (readInProgress) {
            return;
        }

        ChannelPipeline pipeline = pipeline();
        Queue<Object> inboundBuffer = this.inboundBuffer;
        if (inboundBuffer.isEmpty()) {
            readInProgress = true;
            return;
        }

        final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
        final Integer stackDepth = threadLocals.localChannelReaderStackDepth();
        if (stackDepth < MAX_READER_STACK_DEPTH) {
            threadLocals.setLocalChannelReaderStackDepth(stackDepth + 1);
            try {
                for (;;) {
                    Object received = inboundBuffer.poll();
                    if (received == null) {
                        break;
                    }
                    pipeline.fireChannelRead(received);
                }
                pipeline.fireChannelReadComplete();
            } finally {
                threadLocals.setLocalChannelReaderStackDepth(stackDepth);
            }
        } else {
            eventLoop().execute(readTask);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (state < 2) {
            throw new NotYetConnectedException();
        }
        if (state > 2) {
            throw new ClosedChannelException();
        }

        final LocalChannel peer = this.peer;

        writeInProgress = true;
        try {
            for (;;) {
                Object msg = in.current();
                if (msg == null) {
                    break;
                }
                try {
                    peer.inboundBuffer.add(ReferenceCountUtil.retain(msg));
                    in.remove();
                } catch (Throwable cause) {
                    in.remove(cause);
                }
            }
        } finally {
            // The following situation may cause trouble:
            // 1. Write (with promise X)
            // 2. promise X is completed when in.remove() is called, and a listener on this promise calls close()
            // 3. Then the close event will be executed for the peer before the write events, when the write events
            // actually happened before the close event.
            writeInProgress = false;
        }

        finishPeerRead(peer);
    }

    private void finishPeerRead(final LocalChannel peer) {
        // If the peer is also writing, then we must schedule the event on the event loop to preserve read order.
        if (peer.eventLoop() == eventLoop() && !peer.writeInProgress) {
            finishPeerRead0(peer);
        } else {
            peer.eventLoop().execute(new OneTimeTask() {
                @Override
                public void run() {
                    finishPeerRead0(peer);
                }
            });
        }
    }

    private static void finishPeerRead0(LocalChannel peer) {
        ChannelPipeline peerPipeline = peer.pipeline();
        if (peer.readInProgress) {
            peer.readInProgress = false;
            for (;;) {
                Object received = peer.inboundBuffer.poll();
                if (received == null) {
                    break;
                }
                peerPipeline.fireChannelRead(received);
            }
            peerPipeline.fireChannelReadComplete();
        }
    }

    private class LocalUnsafe extends AbstractUnsafe {

        @Override
        public void connect(final SocketAddress remoteAddress,
                SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            if (state == 2) {
                Exception cause = new AlreadyConnectedException();
                safeSetFailure(promise, cause);
                pipeline().fireExceptionCaught(cause);
                return;
            }

            if (connectPromise != null) {
                throw new ConnectionPendingException();
            }

            connectPromise = promise;

            if (state != 1) {
                // Not bound yet and no localAddress specified - get one.
                if (localAddress == null) {
                    localAddress = new LocalAddress(LocalChannel.this);
                }
            }

            if (localAddress != null) {
                try {
                    doBind(localAddress);
                } catch (Throwable t) {
                    safeSetFailure(promise, t);
                    close(voidPromise());
                    return;
                }
            }

            Channel boundChannel = LocalChannelRegistry.get(remoteAddress);
            if (!(boundChannel instanceof LocalServerChannel)) {
                Exception cause = new ChannelException("connection refused");
                safeSetFailure(promise, cause);
                close(voidPromise());
                return;
            }

            LocalServerChannel serverChannel = (LocalServerChannel) boundChannel;
            peer = serverChannel.serve(LocalChannel.this);
        }
    }
}
