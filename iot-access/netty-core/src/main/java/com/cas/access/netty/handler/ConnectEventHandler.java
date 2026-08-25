package com.cas.access.netty.handler;

import com.cas.access.netty.server.GlobalCache;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * @author JHYe
 * @date 2023/10/26
 */
@SuppressWarnings("DuplicatedCode")
@ChannelHandler.Sharable
@Component
@Slf4j
public class ConnectEventHandler extends ChannelInboundHandlerAdapter {

    /**
     * 客户端与服务端创建新连接的时候调用
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        int clientPort = clientAddress.getPort();
        //获取连接通道的唯一标识
        ChannelId channelId = ctx.channel().id();
        GlobalCache.addConnection(channelId, ctx.channel());
        GlobalCache.addSocketChannel(localPort, ctx.channel());

        log.info("[客户端-{}:{}]-<连接>-[NettyServer-{}:{}] - [SPSocketChannelSet.Size:{} - ChannelId:{} - ChannelSize:{}]",
                clientIp, clientPort, localIp, localPort, GlobalCache.getConnectionCount(localPort), channelId, GlobalCache.getTotalConnectionCount());
    }

    /**
     * 客户端与服务端断开连接时调用
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        int clientPort = clientAddress.getPort();
        //获取客户端连接通道的唯一标识
        ChannelId channelId = ctx.channel().id();
        GlobalCache.removeConnection(channelId);
        GlobalCache.removeSocketChannel(localPort, ctx.channel());
        log.info("[客户端-{}:{}]-<断开连接>-[NettyServer-{}:{}] - [SPSocketChannelSet.Size:{} - ChannelId:{} - ChannelSize:{}]",
                clientAddress.getAddress().getHostAddress(), clientPort, localIp, localPort, GlobalCache.getConnectionCount(localPort), channelId, GlobalCache.getTotalConnectionCount());
    }

    /**
     * 用户自定义事件触发器，用于检测超时，如超时断开连接
     *
     * @param ctx
     * @param evt
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        int clientPort = clientAddress.getPort();
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        GlobalCache.removeConnection(channelId);
        GlobalCache.removeSocketChannel(localPort, channel);
        //判断事件类型，如果为IdleStateEvent
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                log.info("[客户端-{}:{}]-<读超时>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.getTotalConnectionCount());
                channel.close(); //超时断开与服务端的连接
            } else if (event.state() == IdleState.WRITER_IDLE) {
                log.info("[客户端-{}:{}]-<写超时>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.getTotalConnectionCount());
                channel.close();  //超时断开与服务端的连接
            } else if (event.state() == IdleState.ALL_IDLE) {
                log.info("[客户端-{}:{}]-<读写超时>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.getTotalConnectionCount());
                channel.close();  //超时断开与服务端的连接
            }
        }
    }

}
