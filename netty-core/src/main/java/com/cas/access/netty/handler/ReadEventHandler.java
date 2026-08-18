package com.cas.access.netty.handler;

import com.cas.access.netty.protocol.MessageBridge;
import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.util.NettyServerUtil;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * 客户端TCP报文处理器类，用于处理客户端TCP报文数据
 * 同时也保存全局的客户端连接情况
 *
 * @author wumengjun  yjh
 */
@ChannelHandler.Sharable
@Component
@Slf4j
//@Scope("prototype")
public class ReadEventHandler extends ChannelInboundHandlerAdapter {

    @Autowired(required = false)
    private MessageBridge messageBridge;

    /**
     * 服务端处理客户端请求的核心方法，这里接收了客户端发来的信息
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object info) {
        //获取此连接通道的唯一标识
        ChannelId channelId = ctx.channel().id();
        String s = info.toString();
        log.info("[客户端-{}:{}]-<Read>-[NettyServer-{}:{}]-[ChannelId:{}] - [源数据:{}]", NettyServerUtil.getClientIp(ctx), NettyServerUtil.getClientPort(ctx), NettyServerUtil.getServerIp(ctx), NettyServerUtil.getServerPort(ctx), channelId, s);

        // 数据桥接：异步提交到业务线程池，不阻塞 Netty IO 线程
        if (messageBridge != null) {
            messageBridge.send(
                    NettyServerUtil.getServerPort(ctx),
                    NettyServerUtil.getServerIp(ctx),
                    NettyServerUtil.getClientPort(ctx),
                    NettyServerUtil.getClientIp(ctx),
                    s
            );
        }
    }

    /**
     * 服务端接收客户端发送过来的数据结束之后调用
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
//        ctx.writeAndFlush("OK");
//        ctx.flush();
    }

    /**
     * 工程出现异常时调用
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        //获取客户端连接通道的唯一标识
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        //获取异常消息
        String causeMessage = cause.getMessage();
        GlobalCache.removeConnection(channelId);
        log.error("[NettyServer-{}:{}]-<异常>-[客户端-{}:{}]-[ChannelId:{}-ChannelSize:{}] - [信息:{}]", NettyServerUtil.getServerIp(ctx), NettyServerUtil.getServerPort(ctx), NettyServerUtil.getClientIp(ctx), NettyServerUtil.getClientPort(ctx), channelId, GlobalCache.getTotalConnectionCount(), causeMessage);
        channel.close();
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent stateEvent = (IdleStateEvent) evt;
            IdleState state = stateEvent.state();

            if (state == IdleState.READER_IDLE) {
                // 读超时：一段时间没有收到数据
                System.out.println("读超时：设备太久没发数据过来");
                // 可以选择关闭连接或发送心跳询问
                ctx.close();

            } else if (state == IdleState.WRITER_IDLE) {
                // 写超时：一段时间没有发送数据
                System.out.println("写超时：太久没向设备发消息了");
                // 通常在这里发送一个心跳包
               ctx.close();

            } else if (state == IdleState.ALL_IDLE) {
                // 读和写都超时了
                System.out.println("读写都超时，连接可能异常");
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
