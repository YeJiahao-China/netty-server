package com.cas.access.netty.handler;

import com.cas.access.netty.server.GlobalCache;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Set;

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
        GlobalCache.CONNECTION_STATUS_MAP.put(channelId, ctx.channel());
        Set<Channel> socketChannels = GlobalCache.SP_SocketChannel_Map.get(localPort);
        socketChannels.add(ctx.channel());
        log.info("[Client-{}:{}]-<连接建立>-[NettyServer-{}:{}] - [SPSocketChannelSet.Size:{} - ChannelId:{} - ChannelSize:{}]",
                clientIp, clientPort, localIp, localPort, socketChannels.size(), channelId, GlobalCache.CONNECTION_STATUS_MAP.size());
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
        String clientIp = ProxyIpDecoder.ChannelId_IP_MAP.get(channelId) == null ? clientAddress.getAddress().getHostAddress() : ProxyIpDecoder.ChannelId_IP_MAP.get(channelId);
        /** 客户端断开连接就从两个全局MAP中移除记录 */
        if (GlobalCache.CONNECTION_STATUS_MAP.containsKey(channelId)) {
            GlobalCache.CONNECTION_STATUS_MAP.remove(channelId);
            ProxyIpDecoder.ChannelId_IP_MAP.remove(channelId);
        }
        Set<Channel> socketChannels = GlobalCache.SP_SocketChannel_Map.get(localPort);
        socketChannels.remove(ctx.channel());
        log.info("[Client-{}:{}]-<断开连接>-[NettyServer-{}:{}] - [SPSocketChannelSet.Size:{} - ChannelId:{} - ChannelSize:{}]",
                clientIp, clientPort, localIp, localPort, socketChannels.size(), channelId, GlobalCache.CONNECTION_STATUS_MAP.size());
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
        GlobalCache.CONNECTION_STATUS_MAP.remove(channelId);
        Set<Channel> socketChannels = GlobalCache.SP_SocketChannel_Map.get(localPort);
        socketChannels.remove(channel);
        //判断事件类型，如果为IdleStateEvent
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                log.info("[Client-{}:{}]-<读超时断开连接>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.CONNECTION_STATUS_MAP.size());
                channel.close(); //超时断开与服务端的连接
            } else if (event.state() == IdleState.WRITER_IDLE) {
                log.info("[Client-{}:{}]-<写超时断开连接>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.CONNECTION_STATUS_MAP.size());
                channel.close();  //超时断开与服务端的连接
            } else if (event.state() == IdleState.ALL_IDLE) {
                log.info("[Client-{}:{}]-<读写超时断开连接>-[NettyServer-{}:{}] - [ChannelId:{}-ChannelSize:{}]", clientIp, clientPort, localIp, localPort, ctx.channel().id(), GlobalCache.CONNECTION_STATUS_MAP.size());
                channel.close();  //超时断开与服务端的连接
            }
        }
    }

}
