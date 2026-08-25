package com.example.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;

/**
 * Echo 业务 Handler：把读到的每行数据加前缀原样回写。
 *
 * 日志前缀统一用 {@code [Echo]}，便于在 netty-server 的日志中筛选定位。
 *
 * @author example
 */
public class EchoHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(EchoHandler.class);

    /** 客户端连上来时发送欢迎语 */
    /** 收到一行数据，原样回写 */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String line = msg.toString();
//        ctx.writeAndFlush("Echo: " + line + "\r\n");
        ctx.fireChannelRead(msg);
    }

}
