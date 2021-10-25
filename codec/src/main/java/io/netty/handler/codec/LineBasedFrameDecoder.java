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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {
    /*
        基于回车换行符的解码器，以\n或者\r\n作为结束符，解码过程就是遍历累积区中
        的字节流，找到\n或者\r\n等结束分隔符，在结束分隔符之前的数据就是需要解码
        的数据。
     */

    /**
     * Maximum length of a frame we're willing to decode.
     * 解码的最大长度
     */
    private final int maxLength;

    /**
     * Whether or not to throw an exception as soon as we exceed maxLength.
     * 数据超过maxLength指定的最大长度后，是否立即抛出异常
     */
    private final boolean failFast;

    /**
     * 解码后的数据是否跳过分隔符\n或\r\n
     */
    private final boolean stripDelimiter;

    /**
     * True if we're discarding input because we're already over maxLength.
     * 数据超过maxLength指定的最大长度后，是否需要丢弃数据
     */
    private boolean discarding;

    /**
     * 丢弃数据的字节数
     */
    private int discardedBytes;

    /** Last scan position. */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // 遍历累积区中的可读字节，看是否有\n或者\r\n，如果有的话，找到对应的索引位置
        final int eol = findEndOfLine(buffer);
        // 非丢弃模式
        if (!discarding) {
            // 非丢弃模式，并且有换行符
            if (eol >= 0) {
                final ByteBuf frame;
                // 可解码的一条消息的长度，也就是读指针到换行符的数据长度
                final int length = eol - buffer.readerIndex();
                // 换行符\n的长度位1，\r\n的长度是2
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                // 读指针到换行符的数据长度大于指定的最大长度
                if (length > maxLength) {
                    // 跳过这段数据
                    buffer.readerIndex(eol + delimLength);
                    // 抛异常
                    fail(ctx, length);
                    return null;
                }

                // 解码后的数据不需要包含换行符
                if (stripDelimiter) {
                    // 读取数据的时候不包含换行符
                    frame = buffer.readRetainedSlice(length);
                    // 读取完数据跳过换行符
                    buffer.skipBytes(delimLength);
                } else {
                    // 解码后的数据需要包含换行符，读取的数据长度需要加上换行符的长度
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            }
            // 非丢弃模式，并且没有找到换行符，没有找到换行符是没法解析数据的，下面只是看数据有没有超过最大长度
            else {
                // 可读数据长度
                final int length = buffer.readableBytes();
                // 可读数据长度超过了指定的最大长度
                if (length > maxLength) {
                    // 设置需要丢弃的字节数长度
                    discardedBytes = length;
                    // 修改读指针，跳过这段丢弃的数据
                    buffer.readerIndex(buffer.writerIndex());
                    // 设置丢弃模式
                    discarding = true;
                    // 跳过了所有数据，偏移量设置为0
                    offset = 0;
                    // 如果开启了快速失败，则直接抛异常
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        }
        // 丢弃模式，上一次解码数据后可能会被设置为丢弃模式
        else {
            // 丢弃模式，并且有换行符
            if (eol >= 0) {
                /*
                    上次要丢弃的字节数 + 本次可读数据长度是需要被丢弃的数据。上次解码时将数据设置为丢弃模式
                    的条件是没有找到换行符，并且数据已经超多了最大长度，所以再次到这里来的时候数据肯定也是超
                    过了最大长度，需要被丢弃，如果现在找到了换行符，则需要将之前的数据以及到现在的换行符的数
                    据都丢弃掉。

                    丢弃完数据后，会重新设置为非丢弃模式
                 */
                final int length = discardedBytes + eol - buffer.readerIndex();
                // 换行符\n的长度位1，\r\n的长度是2
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                // 跳过要丢弃的数据以及换行符
                buffer.readerIndex(eol + delimLength);
                // 重置要丢弃的字节数字段的值位0
                discardedBytes = 0;
                // 重置位非丢弃模式
                discarding = false;
                // 如果不是快速失败，则上次设置为丢弃模式的时候，是没有抛异常的，这次就需要直接抛异常
                if (!failFast) {
                    fail(ctx, length);
                }
            }
            // 丢弃模式，并且这次也没有找到换行符
            else {
                /*
                    需要连同前一次丢弃的数据一起累加，由于这次数据没找到换行符，还需要等到下一次解码继续看
                    能不能找到换行符，如果能找到就需要一起丢弃到；找不到的话，继续再累加并下一次解码继续找
                 */
                discardedBytes += buffer.readableBytes();
                // 跳过本次所有可读数据
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                // 跳过了所有数据，偏移量设置为0
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
