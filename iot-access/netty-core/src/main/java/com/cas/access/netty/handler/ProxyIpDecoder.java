package com.cas.access.netty.handler;

import com.cas.access.netty.enums.TerminatorEnum;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author JHYe
 * @date 2023/9/18
 */
@SuppressWarnings("AlibabaUndefineMagicConstant")
@Slf4j
public class ProxyIpDecoder extends ByteToMessageDecoder {

    /**
     * 缓存ChannelId与客户端真实ip地址的键值对
     */
    public static ConcurrentHashMap<ChannelId, String> ChannelId_IP_MAP = new ConcurrentHashMap<>();

    /**
     * decode() 会根据接收的数据，被调用多次，直到确定没有新的元素添加到list,
     * 或者是 ByteBuf 没有更多的可读字节为止。
     * 如果 list 不为空，就会将 list 的内容传递给下一个 handler
     *
     * @param ctx     上下文对象
     * @param byteBuf 入站后的 ByteBuf
     * @param out     将解码后的数据传递给下一个 handler
     * @throws Exception
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) {

        byte[] bytes = transferToBytes(byteBuf);
        String message = new String(bytes, Charset.forName("UTF-8"));
        if (bytes.length > 0 && message.contains("PROXY")) {
            //判断是否有代理
            //PROXY TCP4 101.106.236.66 192.168.0.150 12646 5683\r\n
            log.info("PROXY MSG: {}", message.substring(0, message.length() - 2));
            if (message.indexOf(TerminatorEnum.Line_Break.getSymbol()) != -1) {
                String[] str = message.split("\n")[0].split(" ");
                String realIp = str[2];
                log.info("Real Client IP: {} - ChannelId: {}", realIp, ctx.channel().id());
                ChannelId_IP_MAP.put(ctx.channel().id(), realIp);
            }
            //清空数据，重要不能省略
            byteBuf.clear();
            return;
        } else {
            // 方式一
//            ByteBuf copyByteBuf = byteBuf.retainedDuplicate();
//            out.add(copyByteBuf);
//            byteBuf.skipBytes(byteBuf.readableBytes());
            // 方式二
//            ctx.fireChannelRead(byteBuf.retainedDuplicate());
            // 方式三
//            ReferenceCountUtil.retain(byteBuf);// 这一步有必要
            ctx.fireChannelRead(byteBuf);
//            out.add(byteBuf);
        }
    }

    /**
     * transferToBytes
     *
     * @param newBuf
     */
    public byte[] transferToBytes(ByteBuf newBuf) {
        ByteBuf copy = newBuf.copy();
        byte[] bytes = new byte[copy.readableBytes()];
        copy.readBytes(bytes);
        return bytes;
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        ChannelId channelId = ctx.channel().id();
        System.out.printf("Channel:%s关闭 - ProxyIpDecoder被移除\n", channelId);
        super.handlerRemoved0(ctx);
    }
}
