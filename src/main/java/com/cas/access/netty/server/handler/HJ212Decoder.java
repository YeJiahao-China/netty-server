package com.cas.access.netty.server.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author JHYe
 * @date 2024/4/25
 */
@Slf4j
public class HJ212Decoder extends ByteToMessageDecoder {

    private static final int DATA_SEGMENT_LEN = 4;
    private static final int CHECK_CODE_LEN = 4;
    private static final int PACKET_HEADER_LEN = "##".getBytes().length;
    private static final int PACKET_TAIL_LEN = "&&".getBytes().length + CHECK_CODE_LEN;

//    @Override
//    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//        // Continue searching for the start delimiter "##" in the incoming data
//        while (in.readableBytes() > 6) { // Need at least 6 bytes to start (## + 4 digits for length)
//            in.markReaderIndex();
//
//            // Search for the start delimiter "##"
//            if (in.readByte() == '#' && in.readByte() == '#') {
//                // Check if there's enough data for the length field
//                if (in.readableBytes() < 4) {
//                    in.resetReaderIndex();
//                    return;
//                }
//
//                byte[] lengthBytes = new byte[4];
//                in.readBytes(lengthBytes);
//                String lengthStr = new String(lengthBytes, StandardCharsets.UTF_8);
//                int length;
//                try {
//                    length = Integer.parseInt(lengthStr);
//                } catch (NumberFormatException e) {
//                    in.resetReaderIndex();
//                    in.readByte(); // Skip one byte to try to find a new start
//                    continue;
//                }
//
//                // Check if the actual data of the specified length is available, plus checksum length (4)
//                if (in.readableBytes() < length + 4) {
//                    in.resetReaderIndex();
//                    return;
//                }
//
//                // Read the message and checksum
//                ByteBuf messageBuf = in.readBytes(length); // Read the message
//                CharSequence s = messageBuf.toString(StandardCharsets.UTF_8);
//
//                while (s.length() < length) {
//                    int remainLength = length - s.length();
//                    messageBuf.writeBytes(in, remainLength);
//                    s = messageBuf.toString(StandardCharsets.UTF_8);
//                }
//
//
////                CharSequence s = in.readCharSequence(length, StandardCharsets.UTF_8); // Read the message (ignore for now
////                ByteBuf frame = in.readBytes(length);
//                byte[] checksumBytes = new byte[4];
//                in.readBytes(checksumBytes);
//
//                // Here, validate the checksum if necessary
////                boolean isValidChecksum = validateChecksum(checksumBytes); // Implement your checksum validation logic
//
////                if (isValidChecksum) {
//                out.add(s.toString());
////                }
//
//                return; // Successfully decoded a frame
//            } else {
//                // If "##" was not found, reset to the marked position to search again
//                in.resetReaderIndex();
//                in.readByte(); // Skip one byte to try to find a new start
//            }
//        }
//    }


//    @Override
//    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//        // Search for the start delimiter "##"
//        if (in.readByte() != '#' || in.readByte() != '#') {
//            throw new IllegalStateException("Invalid start of frame");
//        }
//
//        // Read the length field
//        byte[] lengthBytes = new byte[4];
//        in.readBytes(lengthBytes);
//        String lengthStr = new String(lengthBytes, StandardCharsets.UTF_8);
//        int length = Integer.parseInt(lengthStr);
//
//        // Read the message
//        byte[] messageBytes = new byte[length];
//        in.readBytes(messageBytes);
//        String message = new String(messageBytes, StandardCharsets.UTF_8);
//
//        // Read the end delimiter "&&"
//        if (in.readByte() != '&' || in.readByte() != '&') {
//            throw new IllegalStateException("Invalid end of frame");
//        }
//
//        // Read the checksum
//        byte[] checksumBytes = new byte[4];
//        in.readBytes(checksumBytes);
//
//        out.add(message);
//
//    }


    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 检查输入数据的可读字节数是否足够
        if (in.readableBytes() < 8) {
            in.skipBytes(in.readableBytes());
            return;
        }

        // 读取数据包的开头（两个#）
        if (in.getByte(in.readerIndex()) != '#' || in.getByte(in.readerIndex() + 1) != '#') {
            log.error("数据包无效开头, 丢弃该条消息: {}", in.toString(Charset.defaultCharset()));
            in.skipBytes(in.readableBytes());
            return;
        }
        // 跳过两个#
        in.skipBytes(PACKET_HEADER_LEN);

        // 读取数据长度字段（4个字节）
        byte[] lengthBytes = new byte[DATA_SEGMENT_LEN];
        in.readBytes(lengthBytes);
        String lengthString = new String(lengthBytes, StandardCharsets.UTF_8);
        int dataLength = Integer.parseInt(lengthString);

        // 检查是否有足够的数据
        if (in.readableBytes() < dataLength + PACKET_TAIL_LEN) {
            in.resetReaderIndex(); // 重置读索引返回，代表这次不想读，等待更多数据
            return;
        }

        // 读取数据段
        byte[] dataBytes = new byte[in.readableBytes() - PACKET_TAIL_LEN];
        in.readBytes(dataBytes);
        String dataSegment = new String(dataBytes, StandardCharsets.UTF_8);

        // 读取校验码（4个字节）
        byte[] checkCodeBytes = new byte[CHECK_CODE_LEN];
        in.readBytes(checkCodeBytes);
        String checksum = new String(checkCodeBytes, StandardCharsets.UTF_8);
        // 将解码后的数据对象添加到输出列表中
        out.add(dataSegment);
    }
}
