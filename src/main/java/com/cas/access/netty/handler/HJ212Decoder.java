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
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {


        // 检查输入数据的可读字节数是否足够
        if (in.readableBytes() < 8) {
            in.skipBytes(in.readableBytes());
            return;
        }


//        if(in.refCnt()>0){
//            in.release();
//        }

        // 读取数据包的开头（两个#）
        if (in.getByte(in.readerIndex()) != '#' || in.getByte(in.readerIndex() + 1) != '#') {
            log.error("数据包无效开头, 丢弃该条消息: {}", in.toString(Charset.defaultCharset()));
//            in.retain();
//            in.release();
            in.skipBytes(in.readableBytes());
            return;
        }

        // 跳过两个#
        in.skipBytes(2);

        // 读取数据长度字段（4个字节）
        byte[] lengthBytes = new byte[4];
        in.readBytes(lengthBytes);
        String lengthString = new String(lengthBytes, StandardCharsets.UTF_8);
        int dataLength = Integer.parseInt(lengthString);

        // 检查是否有足够的数据
        if (in.readableBytes() < dataLength) {
            log.error("不正确的数据段长度");
            in.release();
//            in.skipBytes(in.readableBytes());
//            in.resetReaderIndex(); // 重置读索引，等待更多数据
            return;
        }

        // 读取数据段
//        byte[] dataBytes = new byte[dataLength];
        byte[] dataBytes = new byte[in.readableBytes() - 4];
        in.readBytes(dataBytes);
        String dataSegment = new String(dataBytes, StandardCharsets.UTF_8);

        // 读取校验码（4个字节）
        byte[] checksumBytes = new byte[4];
        in.readBytes(checksumBytes);
        String checksum = new String(checksumBytes, StandardCharsets.US_ASCII);

        // 将解码后的数据对象添加到输出列表中
        out.add(dataSegment);

    }
}
