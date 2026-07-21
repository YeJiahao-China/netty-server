package com.cas.access.netty.handler;

import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.util.NettyServerUtil;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

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

    /**
     * 服务端处理客户端请求的核心方法，这里接收了客户端发来的信息
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object info) {
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = null;
        int clientPort = clientAddress.getPort();
        //获取此连接通道的唯一标识
        ChannelId channelId = ctx.channel().id();
        clientIp = ProxyIpDecoder.ChannelId_IP_MAP.get(channelId) == null ? clientAddress.getAddress().getHostAddress() : ProxyIpDecoder.ChannelId_IP_MAP.get(channelId);
        //服务端接收到的数据
        String s = info.toString();
        log.info("[客户端-{}:{}]-<Read>-[NettyServer-{}:{}]-[ChannelId:{}] - [源数据:{}]", clientIp, clientPort, localIp, localPort, channelId, s);
        // 关闭监听服务端口20202
//        NettyServerUtil.closeListen(20202);
        ctx.writeAndFlush("OK");
//        try {
//            Thread.sleep(30000); // 模拟耗时操作
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
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
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        int clientPort = clientAddress.getPort();
        //获取客户端连接通道的唯一标识
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        //获取异常消息
        String causeMessage = cause.getMessage();
        GlobalCache.CONNECTION_STATUS_MAP.remove(channelId);
        log.error("[NettyServer-{}:{}]-<异常>-[客户端-{}:{}]-[ChannelId:{}-ChannelSize:{}] - [信息:{}]", localIp, localPort, clientIp, clientPort, channelId, GlobalCache.CONNECTION_STATUS_MAP.size(), causeMessage);
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
