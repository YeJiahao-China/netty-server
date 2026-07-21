package com.cas.access.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.List;

/**
 * 测试解码器  只需要检查数据开头是否为@@即可
 */
@Slf4j
public class TestDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            log.info("TestDecode解码");
            if (in.readableBytes() < 2) {
                in.skipBytes(in.readableBytes());
                return;
            }
            // 读取数据包的开头（两个#）
            if (in.getByte(in.readerIndex()) != '@' || in.getByte(in.readerIndex() + 1) != '@') {
                log.error("Invalid packet header, discard this message.: {}", in.toString(Charset.defaultCharset()));
                in.skipBytes(in.readableBytes());
                return;
            }
            in.readBytes(2);
            // 剩下的全部内容都作为一个完整帧传递出去（假设协议如此）
            if (in.isReadable()) {
                ByteBuf buf = in.readBytes(in.readableBytes());
                out.add(buf); // 安全传递
            }
//            out.add(in);
        } catch (Exception e) {
            log.error("TestDecoder解码异常：{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
