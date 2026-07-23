package com.example.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
//    @Override
//    public void channelActive(ChannelHandlerContext ctx) {
//        log.info("[Echo] 客户端连接: {}", ctx.channel().remoteAddress());
//        ctx.writeAndFlush("Echo server ready. Send me anything...\r\n");
//    }

    /** 收到一行数据，原样回写 */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String line = msg.toString();
        log.info("[Echo] 收到数据: {}", line);
//        ctx.writeAndFlush("Echo: " + line + "\r\n");
        ctx.fireChannelRead(msg);
    }

//    @Override
//    public void channelInactive(ChannelHandlerContext ctx) {
//        log.info("[Echo] 客户端断开: {}", ctx.channel().remoteAddress());
//    }

//    @Override
//    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//        log.error("[Echo] 异常: {}", cause.getMessage(), cause);
//        ctx.close();
//    }
//
//    @Override
//    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
//        // 简单打印一下 Idle 事件，便于 debug 看超时是否触发
//        Object event = evt.toString();
//        log.warn("[Echo] 收到事件: {}", event);
//        super.userEventTriggered(ctx, evt);
//    }
}
