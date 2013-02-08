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
package io.netty.channel;

import io.netty.buffer.MessageBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.TypeParameterMatcher;

/**
 * Abstract base class which handles messages of a specific type.
 *
 * @param <I>   The type of the messages to handle
 */
public abstract class ChannelOutboundMessageHandlerAdapter<I>
        extends ChannelOperationHandlerAdapter implements ChannelOutboundMessageHandler<I> {

    private final TypeParameterMatcher msgMatcher;

    protected ChannelOutboundMessageHandlerAdapter() {
        this(ChannelOutboundMessageHandlerAdapter.class, 0);
    }

    protected ChannelOutboundMessageHandlerAdapter(
            @SuppressWarnings("rawtypes")
            Class<? extends ChannelOutboundMessageHandlerAdapter> parameterizedHandlerType,
            int messageTypeParamIndex) {
        msgMatcher = TypeParameterMatcher.find(this, parameterizedHandlerType, messageTypeParamIndex);
    }

    @Override
    public MessageBuf<I> newOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
        return Unpooled.messageBuffer();
    }

    @Override
    public void freeOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
        ctx.outboundMessageBuffer().free();
    }

    /**
     * Returns {@code true} if and only if the specified message can be handled by this handler.
     *
     * @param msg the message
     */
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return msgMatcher.match(msg);
    }

    @Override
    public final void flush(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        MessageBuf<Object> in = ctx.outboundMessageBuffer();
        MessageBuf<Object> out = null;
        ChannelPromise nextPromise = promise;
        try {
            for (;;) {
                Object msg = in.poll();
                if (msg == null) {
                    break;
                }

                try {
                    if (!acceptOutboundMessage(msg)) {
                        if (out == null) {
                            out = ctx.nextOutboundMessageBuffer();
                        }
                        out.add(msg);
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    I imsg = (I) msg;
                    try {
                        flush(ctx, imsg);
                    } finally {
                        freeOutboundMessage(imsg);
                    }
                } catch (Throwable t) {
                    if (!promise.isDone()) {
                        promise.setFailure(new PartialFlushException(
                                "faied to encode all messages associated with the future", t));
                        nextPromise = ctx.newPromise();
                    }
                }
            }
        } finally {
            ctx.flush(nextPromise);
        }
    }

    /**
     * Is called once a message is being flushed.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
     * @param msg           the message to handle
     * @throws Exception    thrown when an error accour
     */
    protected abstract void flush(ChannelHandlerContext ctx, I msg) throws Exception;

    /**
     * Is called after a message was processed via {@link #flush(ChannelHandlerContext, Object)} to free
     * up any resources that is held by the outbound message. You may want to override this if your implementation
     * just pass-through the input message or need it for later usage.
     */
    protected void freeOutboundMessage(I msg) throws Exception {
        ChannelHandlerUtil.freeMessage(msg);
    }
}
