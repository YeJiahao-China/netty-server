package com.cas.access.netty.server.util;

import com.cas.access.netty.NettyServerBootstrap;
import com.cas.access.netty.server.ServerConnectionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author JHYe
 * @date 2023/10/27
 */
@Slf4j
public class ServerSupport {

    /**
     * 新增服务监听端口
     */
    public static int bindPort(int port) {
        try {
            if (ServerConnectionManager.portServerChannelMap.containsKey(port) || ServerConnectionManager.portSocketChannelMap.containsKey(port)) {
                throw new RuntimeException("端口已被监听");
            }
            ChannelFuture startFuture = NettyServerBootstrap.serverBootstrap.bind(NettyServerBootstrap.localIp,port).sync().addListener(future -> {
                if (future.isSuccess()) {
                    log.info("NettyServer - 监听端口{}绑定成功", port);
                } else {
                    log.error("NettyServer - 监听端口{}绑定失败:{}", port, future.cause().getMessage());
                }
            });
            NioServerSocketChannel channel = (NioServerSocketChannel) startFuture.channel();
            ServerConnectionManager.portServerChannelMap.put(port, channel);
            ServerConnectionManager.portSocketChannelMap.put(port, new HashSet<>());
        } catch (Exception e) {
            log.error("开启监听端口{}启动异常:{}", port, e.getMessage());
            return 500;
        }
        return 200;
    }

    /**
     * 关闭端口监听
     *
     * @param port
     */
    public static int closeListen(int port) {
        try {
            if (ServerConnectionManager.portServerChannelMap.containsKey(port)) {
                // 关闭对应的客户端连接
                Set<NioSocketChannel> socketChannelSet = ServerConnectionManager.portSocketChannelMap.get(port);
                Iterator<NioSocketChannel> iterator = socketChannelSet.iterator();
                while (iterator.hasNext()) {
                    Channel socketChannel = iterator.next();
                    InetSocketAddress socketAddress = (InetSocketAddress) socketChannel.remoteAddress();
                    socketChannel.close().addListener(future -> {
                        if (future.isSuccess()) {
                            log.info("NettyServer主动关闭客户端[{}:{}]成功", socketAddress.getAddress().getHostAddress(), socketAddress.getPort());
                        } else {
                            log.error("NettyServer主动关闭客户端[{}:{}]失败, 失败信息:{}", socketAddress.getAddress().getHostAddress(), socketAddress.getPort(), future.cause().getMessage());
                        }
                    });
                    ServerConnectionManager.totalConnectionMap.remove(socketChannel.id());
                }
                // 正式关闭监听端口
                Channel serverSocketChannel = ServerConnectionManager.portServerChannelMap.get(port);
                InetSocketAddress serverAddress = (InetSocketAddress) serverSocketChannel.localAddress();
                serverSocketChannel.close();
                ServerConnectionManager.portServerChannelMap.remove(port);
                ServerConnectionManager.portSocketChannelMap.remove(port);
                log.info("NettyServer关闭监听端口[{}:{}]", serverAddress.getAddress().getHostAddress(), port);
            }
            return 200;
        } catch (Exception e) {
            log.error("NettyServer关闭监听端口{}异常:{}", port, e.getMessage());
            return 500;
        }
    }

}
