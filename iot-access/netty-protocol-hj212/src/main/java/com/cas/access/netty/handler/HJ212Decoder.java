package com.cas.access.netty.handler;

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

    /** 最大允许的数据段长度，防止恶意长度字段导致 OOM */
    private static final int MAX_DATA_LENGTH = 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        // 检查输入数据的可读字节数是否足够
        if (in.readableBytes() < 10) {
            // 数据不够等待数据
            log.info("ByteBuf可读数据不足，将重置读索引，等待完整数据!!!");
            in.resetReaderIndex();
            return;
        }

        // 读取数据包的开头（两个#）
        if (in.getByte(in.readerIndex()) != '#' || in.getByte(in.readerIndex() + 1) != '#') {
            log.info("HJ212解码器读取到不可用的数据包header,跳过1个字节等待下次读取: {}", in.toString(Charset.defaultCharset()));
            in.skipBytes(1);
            return;
        }

        // 跳过两个#
        in.skipBytes(2);

        // 读取数据长度字段（4个字节）
        byte[] lengthBytes = new byte[4];
        in.readBytes(lengthBytes);
        String lengthString = new String(lengthBytes, StandardCharsets.UTF_8);

        int dataLength;
        try {
            dataLength = Integer.parseInt(lengthString);
        } catch (NumberFormatException e) {
            log.warn("HJ212 长度字段非法: '{}', 跳过该帧头", lengthString);
            return;
        }

        if (dataLength < 0 || dataLength > MAX_DATA_LENGTH) {
            log.warn("HJ212 数据段长度非法: {}, 跳过该帧头", dataLength);
            return;
        }

        // 检查是否有足够的数据（数据段 + 4字节CRC）
        if (in.readableBytes() < dataLength + 4) {
            log.info("ByteBuf可读数据小于完整帧长度，将重置读索引，等待完整数据!!!");
            in.resetReaderIndex(); // 重置读索引，等待更多数据
            return;
        }

        byte[] bytes = new byte[dataLength];
        in.readBytes(bytes);
        String dataSegment = new String(bytes, StandardCharsets.UTF_8);
        log.info("成功解码数据：{}",dataSegment);
        // 读取数据段
//        byte[] dataBytes = new byte[dataLength];
//        byte[] dataBytes = new byte[in.readableBytes() - 4];
//        in.readBytes(dataBytes);
//        String dataSegment = new String(dataBytes, StandardCharsets.UTF_8);
//        log.info("成功解码数据：{}",dataSegment);
        // 读取校验码（4个字节）
        byte[] checksumBytes = new byte[4];
        in.readBytes(checksumBytes);
        String checksum = new String(checksumBytes, StandardCharsets.US_ASCII);

        // 将解码后的数据对象添加到输出列表中
        out.add(dataSegment);

    }
}
