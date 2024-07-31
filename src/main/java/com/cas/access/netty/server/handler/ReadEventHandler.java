package com.cas.access.netty.server.handler;

import com.cas.access.netty.server.ServerConnectionManager;
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
        log.info("[Client-{}:{}]-<Read>-[NettyServer-{}:{}]-[ChannelId:{}] - [Body:{}]", clientIp, clientPort, localIp, localPort, channelId, s);
        // 关闭监听服务端口20202
//        channelReadComplete(ctx);

//        ctx.writeAndFlush(info.toString()).addListener(new ChannelFutureListener() {
//            @Override
//            public void operationComplete(ChannelFuture future) throws Exception {
//                if (future.isDone() || future.isSuccess()) {
//                    log.info("通道写操作成功");
//                } else {
//                    log.error("通道不可写, 写缓冲区ChannelOutboundBuffer疑似达到高水位线");
//                }
//            }
//        });

    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
//        log.info("SocketChannel[IP:{},PORT:{}] Registered", socketAddress.getHostString(), socketAddress.getPort());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
//        log.info("SocketChannel[IP:{},PORT:{}] Activated", socketAddress.getHostString(), socketAddress.getPort());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
//        log.info("Client[IP:{},PORT:{} Disconnect]", socketAddress.getHostString(), socketAddress.getPort());
    }

    /**
     * 服务端接收客户端发送过来的数据结束之后调用
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().localAddress();
//        log.info("Client[IP:{},PORT:{} ReadComplete]", socketAddress.getHostString(), socketAddress.getPort());
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
        ServerConnectionManager.totalConnectionMap.remove(channelId);

        log.error("[NettyServer-{}:{}]-<Exception>-[Client-{}:{}]-[ChannelId:{}-ChannelSize:{}] - [Cause:{}]", localIp, localPort, clientIp, clientPort, channelId, ServerConnectionManager.totalConnectionMap.size(), causeMessage);
        channel.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt)  {
        //获取服务端、客户端连接的IP和PORT
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        String localIp = localAddress.getAddress().getHostAddress();
        int localPort = localAddress.getPort();
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        int clientPort = clientAddress.getPort();
        Channel channel = ctx.channel();
        ChannelId channelId = channel.id();
        ServerConnectionManager.totalConnectionMap.remove(channelId);
        //判断事件类型，如果为IdleStateEvent
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                log.info("[TcpServer-(IP:{})-(PORT:{})-(ChannelId:{})]-<ReadTimeout>-[Client-(IP:{})-(PORT:{})]; ChannelSize:{};", localIp, localPort, ctx.channel().id(), clientIp, clientPort, ServerConnectionManager.totalConnectionMap.size());
                channel.close(); //超时断开与服务端的连接
            } else if (event.state() == IdleState.WRITER_IDLE) {
                log.info("[TcpServer-(IP:{})-(PORT:{})-(ChannelId:{})]-<WriteTimeout>-[Client-(IP:{})-(PORT:{})]; ChannelSize:{}; ", localIp, localPort, ctx.channel().id(), clientIp, clientPort, ServerConnectionManager.totalConnectionMap.size());
                channel.close();  //超时断开与服务端的连接
            } else if (event.state() == IdleState.ALL_IDLE) {
                log.info("[TcpServer-(IP:{})-(PORT:{})-(ChannelId:{})]-<ReadWriteTimeout>-[Client-(IP:{})-(PORT:{})]; ChannelSize:{}; ", localIp, localPort, ctx.channel().id(), clientIp, clientPort, ServerConnectionManager.totalConnectionMap.size());
                channel.close();  //超时断开与服务端的连接
            }
        }
    }
}
