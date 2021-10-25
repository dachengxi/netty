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

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import java.nio.ByteOrder;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * A decoder that splits the received {@link ByteBuf}s dynamically by the
 * value of the length field in the message.  It is particularly useful when you
 * decode a binary message which has an integer header field that represents the
 * length of the message body or the whole message.
 * <p>
 * {@link LengthFieldBasedFrameDecoder} has many configuration parameters so
 * that it can decode any message with a length field, which is often seen in
 * proprietary client-server protocols. Here are some example that will give
 * you the basic idea on which option does what.
 *
 * <h3>2 bytes length field at offset 0, do not strip header</h3>
 *
 * The value of the length field in this example is <tt>12 (0x0C)</tt> which
 * represents the length of "HELLO, WORLD".  By default, the decoder assumes
 * that the length field represents the number of the bytes that follows the
 * length field.  Therefore, it can be decoded with the simplistic parameter
 * combination.
 * <pre>
 * <b>lengthFieldOffset</b>   = <b>0</b>
 * <b>lengthFieldLength</b>   = <b>2</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0 (= do not strip header)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, strip header</h3>
 *
 * Because we can get the length of the content by calling
 * {@link ByteBuf#readableBytes()}, you might want to strip the length
 * field by specifying <tt>initialBytesToStrip</tt>.  In this example, we
 * specified <tt>2</tt>, that is same with the length of the length field, to
 * strip the first two bytes.
 * <pre>
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 2
 * lengthAdjustment    = 0
 * <b>initialBytesToStrip</b> = <b>2</b> (= the length of the Length field)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)
 * +--------+----------------+      +----------------+
 * | Length | Actual Content |----->| Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |
 * +--------+----------------+      +----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, do not strip header, the length field
 *     represents the length of the whole message</h3>
 *
 * In most cases, the length field represents the length of the message body
 * only, as shown in the previous examples.  However, in some protocols, the
 * length field represents the length of the whole message, including the
 * message header.  In such a case, we specify a non-zero
 * <tt>lengthAdjustment</tt>.  Because the length value in this example message
 * is always greater than the body length by <tt>2</tt>, we specify <tt>-2</tt>
 * as <tt>lengthAdjustment</tt> for compensation.
 * <pre>
 * lengthFieldOffset   =  0
 * lengthFieldLength   =  2
 * <b>lengthAdjustment</b>    = <b>-2</b> (= the length of the Length field)
 * initialBytesToStrip =  0
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000E | "HELLO, WORLD" |      | 0x000E | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the end of 5 bytes header, do not strip header</h3>
 *
 * The following message is a simple variation of the first example.  An extra
 * header value is prepended to the message.  <tt>lengthAdjustment</tt> is zero
 * again because the decoder always takes the length of the prepended data into
 * account during frame length calculation.
 * <pre>
 * <b>lengthFieldOffset</b>   = <b>2</b> (= the length of Header 1)
 * <b>lengthFieldLength</b>   = <b>3</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
 * |  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the beginning of 5 bytes header, do not strip header</h3>
 *
 * This is an advanced example that shows the case where there is an extra
 * header between the length field and the message body.  You have to specify a
 * positive <tt>lengthAdjustment</tt> so that the decoder counts the extra
 * header into the frame length calculation.
 * <pre>
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 3
 * <b>lengthAdjustment</b>    = <b>2</b> (= the length of Header 1)
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
 * | 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field</h3>
 *
 * This is a combination of all the examples above.  There are the prepended
 * header before the length field and the extra header after the length field.
 * The prepended header affects the <tt>lengthFieldOffset</tt> and the extra
 * header affects the <tt>lengthAdjustment</tt>.  We also specified a non-zero
 * <tt>initialBytesToStrip</tt> to strip the length field and the prepended
 * header from the frame.  If you don't want to strip the prepended header, you
 * could specify <tt>0</tt> for <tt>initialBytesToSkip</tt>.
 * <pre>
 * lengthFieldOffset   = 1 (= the length of HDR1)
 * lengthFieldLength   = 2
 * <b>lengthAdjustment</b>    = <b>1</b> (= the length of HDR2)
 * <b>initialBytesToStrip</b> = <b>3</b> (= the length of HDR1 + LEN)
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field, the length field
 *     represents the length of the whole message</h3>
 *
 * Let's give another twist to the previous example.  The only difference from
 * the previous example is that the length field represents the length of the
 * whole message instead of the message body, just like the third example.
 * We have to count the length of HDR1 and Length into <tt>lengthAdjustment</tt>.
 * Please note that we don't need to take the length of HDR2 into account
 * because the length field already includes the whole header length.
 * <pre>
 * lengthFieldOffset   =  1
 * lengthFieldLength   =  2
 * <b>lengthAdjustment</b>    = <b>-3</b> (= the length of HDR1 + LEN, negative)
 * <b>initialBytesToStrip</b> = <b> 3</b>
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 * @see LengthFieldPrepender
 */
public class LengthFieldBasedFrameDecoder extends ByteToMessageDecoder {

    /*
        通用解码器，通常可以将消息分为头和消息体两部分，在协议头中会有专门指定长度的字段
        示例1
        解码前的数据长度14个字节             解码后的数据长度14个字节
        +--------+----------------+      +--------+----------------+
        | Length | Actual Content |----->| Length | Actual Content |
        |   12   | "HELLO, WORLD" |      |   12   | "HELLO, WORLD" |
        +--------+----------------+      +--------+----------------+
        表示长度的域是2个字节，长度域中数据长度是12个字节，实际数据的长度是12个字节，
        接受到数据总长度是14个字节，解码后的数据是接收到的整个数据14个字节：
        - lengthFieldOffset=0，长度域偏移为0，开始的2个字节就是长度域
        - lengthFieldLength=2，长度域的字节数是2
        - lengthAdjustment=0，长度域只包含数据的长度，不需要修正
        - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据

        示例2
        解码前的数据长度14个字节             解码后的数据长度12个字节
        +--------+----------------+      +----------------+
        | Length | Actual Content |----->| Actual Content |
        |   12   | "HELLO, WORLD" |      | "HELLO, WORLD" |
        +--------+----------------+      +----------------+
        表示长度的域是2个字节，长度域中数据长度是12个字节，实际数据长度是12个字节，
        接收到数据总长度是14个字节，解码后的数据是除了长度外的实际数据12个字节：
        - lengthFieldOffset=0，长度域偏移为0，开始的两个字节就是长度域
        - lengthFieldLength=2，长度域的字节数是2
        - lengthAdjustment=0，长度域只包含数据的长度，不需要修正
        - initialBytesToStrip=2，解码后的数据不包含长度域，所以需要跳过2个字节的长度

        示例3
        解码前的数据长度14个字节             解码后的数据长度14个字节
        +--------+----------------+      +--------+----------------+
        | Length | Actual Content |----->| Length | Actual Content |
        |   14   | "HELLO, WORLD" |      |   14   | "HELLO, WORLD" |
        +--------+----------------+      +--------+----------------+
        表示长度的域是2个字节，长度域中数据长度是14个字节，实际数据长度是12个字节，
        接收到数据总长度是14个字节，解码后的数据是接收到的整个数据14个字节：
        - lengthFieldOffset=0，长度域偏移为0，开始的两个字节就是长度域
        - lengthFieldLength=2，长度域的字节数是2
        - lengthAdjustment=-2，长度域中的数据是14，包含了长度域和实际数据长度，所以需要修正：减去2
        - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据

        示例4
        解码前的数据长度17个字节                          解码后的数据长度17个字节
        +----------+----------+----------------+      +----------+----------+----------------+
        | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
        |  0xCAFE  |    12    | "HELLO, WORLD" |      |  0xCAFE  |    12    | "HELLO, WORLD" |
        +----------+----------+----------------+      +----------+----------+----------------+
        最开始的2个字节中数据是额外的Header数据，表示长度的域是3个字节，长度域中数据长度是12个字节，
        实际数据长度是12个字节，接收到数据的总长度是17个字节，解码后的数据是接收到的整个数据17个字节：
        - lengthFieldOffset=2，长度域偏移为2，开始的两个字节是额外的Header的长度，需要跳过
        - lengthFieldLength=3，长度域的字节数是3
        - lengthAdjustment=0，长度域只包含数据的长度，不需要修正
        - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据

        示例5
        解码前的数据长度17个字节                          解码后的数据长度17个字节
        +----------+----------+----------------+      +----------+----------+----------------+
        |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
        |    12    |  0xCAFE  | "HELLO, WORLD" |      |    12    |  0xCAFE  | "HELLO, WORLD" |
        +----------+----------+----------------+      +----------+----------+----------------+
        表示长度域的是3个字节，跟在长度域后面的2个字节长度是额外的Header数据，长度域中数据长度是12个字节，
        实际数据长度是12个字节，接收到的数据总长度是17个字节，解码后的数据是接收到的整个数据17个字节：
        - lengthFieldOffset=0，长度域偏移为0，开始的3个字节就是长度域
        - lengthFieldLength=3，长度域的字节数是3
        - lengthAdjustment=2，长度域中12字节是实际数据的长度，我们要处理的数据是额外Header数据+实际数据，所以要把长度+2
        - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据

        示例6
        解码前的数据长度16个字节                           解码后的数据长度13个字节
        +------+--------+------+----------------+      +------+----------------+
        | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
        | 0xCA |   12   | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
        +------+--------+------+----------------+      +------+----------------+
        最开始的一个字节是HDR1数据，接下来是2个字节的长度域，长度域后面的是一个字节的HDR2数据，
        长度域中数据长度是12个字节，实际数据长度是12个字节，接收到的数据总长度是16个字节，解码后的数据是
        HDR2+实际数据=13个字节：
        - lengthFieldOffset=1，长度域偏移为1，最开始的1个字节是HDR1数据，需要跳过
        - lengthFieldLength=2，长度域的字节数是2
        - lengthAdjustment=1，长度域中12字节是实际数据的长度，我们要处理的数据是HDE2数据+实际数据，所以要把长度+1
        - initialBytesToStrip=3，解码后的数据不包含HDR1和长度域，所以要跳过3个字节

        示例7
        解码前的数据长度是16个字节                         解码后的数据长度是13个字节
        +------+--------+------+----------------+      +------+----------------+
        | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
        | 0xCA |   16   | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
        +------+--------+------+----------------+      +------+----------------+
        最开始的一个字节是HDR1数据，接下来是2个字节的长度域，长度域后面的是一个字节的HDR2数据，
        长度域中数据长度是16个字节，实际数据长度是12个字节，接收到的数据总长度是16个字节，解码后的数据是
        HDR2+实际数据=13个字节：
        - lengthFieldOffset=1，长度域偏移为1，最开始的1个字节是HDR1数据，需要跳过
        - lengthFieldLength=2，长度域的字节数是2
        - lengthAdjustment=-3，长度域中12字节是实际数据的长度，我们要处理的数据是HDE2数据+实际数据，所以要把长度-3
        - initialBytesToStrip=3，解码后的数据不包含HDR1和长度域，所以要跳过3个字节
     */

    private final ByteOrder byteOrder;

    /**
     * 最大帧长度，就是可以接收的数据的最大长度，如果数据长度超过这个，则丢弃掉。
     */
    private final int maxFrameLength;

    /**
     * 数据开始的几个字节可能不是表示数据长度，需要跳过前面的几个字节
     */
    private final int lengthFieldOffset;

    /**
     * 长度域的字节数，表示这个长度域有多少字节
     */
    private final int lengthFieldLength;

    /**
     * 长度域结束的偏移量，是通过lengthFieldOffset + lengthFieldLength计算出来的
     */
    private final int lengthFieldEndOffset;

    /**
     * 数据长度修正
     */
    private final int lengthAdjustment;

    /**
     * 需要跳过的字节数
     */
    private final int initialBytesToStrip;
    private final boolean failFast;

    /**
     * 设置是否需要将过长的消息丢弃掉
     */
    private boolean discardingTooLongFrame;

    /**
     * 消息过长的长度
     */
    private long tooLongFrameLength;

    /**
     * 过长的消息丢弃的字节数
     */
    private long bytesToDiscard;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength) {
        this(maxFrameLength, lengthFieldOffset, lengthFieldLength, 0, 0);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip) {
        this(
                maxFrameLength,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment,
                initialBytesToStrip, true);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     * @param failFast
     *        If <tt>true</tt>, a {@link TooLongFrameException} is thrown as
     *        soon as the decoder notices the length of the frame will exceed
     *        <tt>maxFrameLength</tt> regardless of whether the entire frame
     *        has been read.  If <tt>false</tt>, a {@link TooLongFrameException}
     *        is thrown after the entire frame that exceeds <tt>maxFrameLength</tt>
     *        has been read.
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {
        this(
                ByteOrder.BIG_ENDIAN, maxFrameLength, lengthFieldOffset, lengthFieldLength,
                lengthAdjustment, initialBytesToStrip, failFast);
    }

    /**
     * Creates a new instance.
     *
     * @param byteOrder
     *        the {@link ByteOrder} of the length field
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     * @param failFast
     *        If <tt>true</tt>, a {@link TooLongFrameException} is thrown as
     *        soon as the decoder notices the length of the frame will exceed
     *        <tt>maxFrameLength</tt> regardless of whether the entire frame
     *        has been read.  If <tt>false</tt>, a {@link TooLongFrameException}
     *        is thrown after the entire frame that exceeds <tt>maxFrameLength</tt>
     *        has been read.
     */
    public LengthFieldBasedFrameDecoder(
            ByteOrder byteOrder, int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {

        this.byteOrder = checkNotNull(byteOrder, "byteOrder");

        checkPositive(maxFrameLength, "maxFrameLength");

        checkPositiveOrZero(lengthFieldOffset, "lengthFieldOffset");

        checkPositiveOrZero(initialBytesToStrip, "initialBytesToStrip");

        if (lengthFieldOffset > maxFrameLength - lengthFieldLength) {
            throw new IllegalArgumentException(
                    "maxFrameLength (" + maxFrameLength + ") " +
                    "must be equal to or greater than " +
                    "lengthFieldOffset (" + lengthFieldOffset + ") + " +
                    "lengthFieldLength (" + lengthFieldLength + ").");
        }

        this.maxFrameLength = maxFrameLength;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        // 长度域结束偏移量 = 长度域偏移量 + 长度域的长度
        this.lengthFieldEndOffset = lengthFieldOffset + lengthFieldLength;
        this.initialBytesToStrip = initialBytesToStrip;
        this.failFast = failFast;
    }

    /**
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            表示累积器已经累积的字节流数据
     * @param out           表示可从累积的数据中解码出来的结果列表
     * @throws Exception
     */
    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // LengthFieldBasedFrameDecoder的具体解码过程
        Object decoded = decode(ctx, in);
        // 如果能解码出来数据，就将解码后的结果放到out这个List中，这个解码后的结果会继续传给后续的ChannelHandler进行处理
        if (decoded != null) {
            out.add(decoded);
        }
    }

    private void discardingTooLongFrame(ByteBuf in) {
        long bytesToDiscard = this.bytesToDiscard;
        int localBytesToDiscard = (int) Math.min(bytesToDiscard, in.readableBytes());
        in.skipBytes(localBytesToDiscard);
        bytesToDiscard -= localBytesToDiscard;
        this.bytesToDiscard = bytesToDiscard;

        failIfNecessary(false);
    }

    private static void failOnNegativeLengthField(ByteBuf in, long frameLength, int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "negative pre-adjustment length field: " + frameLength);
    }

    private static void failOnFrameLengthLessThanLengthFieldEndOffset(ByteBuf in,
                                                                      long frameLength,
                                                                      int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than lengthFieldEndOffset: " + lengthFieldEndOffset);
    }

    private void exceededFrameLength(ByteBuf in, long frameLength) {
        long discard = frameLength - in.readableBytes();
        tooLongFrameLength = frameLength;

        if (discard < 0) {
            // buffer contains more bytes then the frameLength so we can discard all now
            in.skipBytes((int) frameLength);
        } else {
            // Enter the discard mode and discard everything received so far.
            discardingTooLongFrame = true;
            bytesToDiscard = discard;
            in.skipBytes(in.readableBytes());
        }
        failIfNecessary(true);
    }

    private static void failOnFrameLengthLessThanInitialBytesToStrip(ByteBuf in,
                                                                     long frameLength,
                                                                     int initialBytesToStrip) {
        in.skipBytes((int) frameLength);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than initialBytesToStrip: " + initialBytesToStrip);
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   in              the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        // 处理如果消息过长需要丢弃一部分数据
        if (discardingTooLongFrame) {
            discardingTooLongFrame(in);
        }

        /*
            lengthFieldEndOffset
            长度域结束偏移量 = 长度域偏移量 + 长度域的长度
            如果当前可读的字节数比长度域结束偏移量小，说明数据不全，连长度域都没法读取，直接返回不读。
            会等待接收下一次的数据后，再进行解析
         */
        if (in.readableBytes() < lengthFieldEndOffset) {
            return null;
        }

        /*
            实际长度域的偏移量
            比如这种情况：
            示例4
            解码前的数据长度17个字节                          解码后的数据长度17个字节
            +----------+----------+----------------+      +----------+----------+----------------+
            | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
            |  0xCAFE  |    12    | "HELLO, WORLD" |      |  0xCAFE  |    12    | "HELLO, WORLD" |
            +----------+----------+----------------+      +----------+----------+----------------+
            最开始的2个字节中数据是额外的Header数据，表示长度的域是3个字节，长度域中数据长度是12个字节，
            实际数据长度是12个字节，接收到数据的总长度是17个字节，解码后的数据是接收到的整个数据17个字节：
            - lengthFieldOffset=2，长度域偏移为2，开始的两个字节是额外的Header的长度，需要跳过
            - lengthFieldLength=3，长度域的字节数是3
            - lengthAdjustment=0，长度域只包含数据的长度，不需要修正
            - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据
            这其中readerIndex=0，lengthFieldOffset=2，所以actualLengthFieldOffset=2

            下面这种情况：
            示例1
            解码前的数据长度14个字节             解码后的数据长度14个字节
            +--------+----------------+      +--------+----------------+
            | Length | Actual Content |----->| Length | Actual Content |
            |   12   | "HELLO, WORLD" |      |   12   | "HELLO, WORLD" |
            +--------+----------------+      +--------+----------------+
            表示长度的域是2个字节，长度域中数据长度是12个字节，实际数据的长度是12个字节，
            接受到数据总长度是14个字节，解码后的数据是接收到的整个数据14个字节：
            - lengthFieldOffset=0，长度域偏移为0，开始的2个字节就是长度域
            - lengthFieldLength=2，长度域的字节数是2
            - lengthAdjustment=0，长度域只包含数据的长度，不需要修正
            - initialBytesToStrip=0，要解码的数据前后长度一样，不需要跳过任何字节数据
            这其中readerIndex=0，lengthFieldOffset=0，所以actualLengthFieldOffset=0
         */
        int actualLengthFieldOffset = in.readerIndex() + lengthFieldOffset;
        // 读取长度域中的长度
        long frameLength = getUnadjustedFrameLength(in, actualLengthFieldOffset, lengthFieldLength, byteOrder);

        // 长度域中的长度小于0，抛异常
        if (frameLength < 0) {
            failOnNegativeLengthField(in, frameLength, lengthFieldEndOffset);
        }

        /*
            lengthFieldEndOffset = lengthFieldOffset + lengthFieldLength
            frameLength = frameLength + lengthAdjustment + lengthFieldOffset + lengthFieldLength;
            这里计算出来就是解码后的数据长度
         */
        frameLength += lengthAdjustment + lengthFieldEndOffset;

        // 实际要解码的数据长度比长度域的长度还小，抛异常
        if (frameLength < lengthFieldEndOffset) {
            failOnFrameLengthLessThanLengthFieldEndOffset(in, frameLength, lengthFieldEndOffset);
        }

        // 超过了最大的数据长度，丢弃掉
        if (frameLength > maxFrameLength) {
            exceededFrameLength(in, frameLength);
            return null;
        }

        // never overflows because it's less than maxFrameLength
        int frameLengthInt = (int) frameLength;
        // 可读的数据比要的数据小，返回null，等待下一次解码
        if (in.readableBytes() < frameLengthInt) {
            return null;
        }

        // 要跳过的字节大于数据长度，抛异常
        if (initialBytesToStrip > frameLengthInt) {
            failOnFrameLengthLessThanInitialBytesToStrip(in, frameLength, initialBytesToStrip);
        }
        // 跳过initialBytesToStrip指定的字节数
        in.skipBytes(initialBytesToStrip);

        // extract frame
        // 当前读指针
        int readerIndex = in.readerIndex();
        // 实际需要的数据长度
        int actualFrameLength = frameLengthInt - initialBytesToStrip;
        // 抽取从readerIndex开始，长度是actualFrameLength的数据
        ByteBuf frame = extractFrame(ctx, in, readerIndex, actualFrameLength);
        // 移动读指针
        in.readerIndex(readerIndex + actualFrameLength);
        // 返回读取到的数据，这个数据将被添加到out这个List中，后续在ByteToMessageDecoder中会被传到后面的ChannelHandler继续处理
        return frame;
    }

    /**
     * Decodes the specified region of the buffer into an unadjusted frame length.  The default implementation is
     * capable of decoding the specified region into an unsigned 8/16/24/32/64 bit integer.  Override this method to
     * decode the length field encoded differently.  Note that this method must not modify the state of the specified
     * buffer (e.g. {@code readerIndex}, {@code writerIndex}, and the content of the buffer.)
     *
     * @throws DecoderException if failed to decode the specified region
     */
    protected long getUnadjustedFrameLength(ByteBuf buf, int offset, int length, ByteOrder order) {
        buf = buf.order(order);
        long frameLength;
        switch (length) {
        case 1:
            frameLength = buf.getUnsignedByte(offset);
            break;
        case 2:
            frameLength = buf.getUnsignedShort(offset);
            break;
        case 3:
            frameLength = buf.getUnsignedMedium(offset);
            break;
        case 4:
            frameLength = buf.getUnsignedInt(offset);
            break;
        case 8:
            frameLength = buf.getLong(offset);
            break;
        default:
            throw new DecoderException(
                    "unsupported lengthFieldLength: " + lengthFieldLength + " (expected: 1, 2, 3, 4, or 8)");
        }
        return frameLength;
    }

    private void failIfNecessary(boolean firstDetectionOfTooLongFrame) {
        if (bytesToDiscard == 0) {
            // Reset to the initial state and tell the handlers that
            // the frame was too large.
            long tooLongFrameLength = this.tooLongFrameLength;
            this.tooLongFrameLength = 0;
            discardingTooLongFrame = false;
            if (!failFast || firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        } else {
            // Keep discarding and notify handlers if necessary.
            if (failFast && firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        }
    }

    /**
     * Extract the sub-region of the specified buffer.
     */
    protected ByteBuf extractFrame(ChannelHandlerContext ctx, ByteBuf buffer, int index, int length) {
        return buffer.retainedSlice(index, length);
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "Adjusted frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "Adjusted frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }
}
