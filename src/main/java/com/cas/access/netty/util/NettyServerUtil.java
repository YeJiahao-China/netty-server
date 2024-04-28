package com.cas.access.netty.util;

import com.cas.access.netty.NettyServerBootstrap;
import com.cas.access.netty.server.GlobalCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author JHYe
 * @date 2023/10/27
 */
@Slf4j
public class NettyServerUtil {

    /**
     * 新增服务监听端口
     */
    public static void bindPort(int port) {
        try {
            ChannelFuture startFuture = NettyServerBootstrap.serverBootstrap.bind("0.0.0.0", port).addListener(future -> {
                if (future.isSuccess()) {
                    log.info("NettyServer - 新增端口{}绑定成功", port);
                } else {
                    log.error("NettyServer - 新增端口{}绑定失败:{}", port, future.cause().getMessage());
                }
            }).sync().addListener(future -> {
                if (future.isSuccess()) {
                    log.info("NettyServer - 新增监听端口{}{}", port, "成功");
                } else {
                    log.error("NettyServer - 新增监听端口{}{}", port, "失败");
                }
            });
            GlobalCache.SP_ServerChannel_Map.put(port, startFuture.channel());
            GlobalCache.SP_SocketChannel_Map.put(port, new HashSet<>());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 关闭端口监听
     *
     * @param port
     */
    public static void closeListen(int port) {
        if (GlobalCache.SP_ServerChannel_Map.containsKey(port)) {
            Channel serverSocketChannel = GlobalCache.SP_ServerChannel_Map.get(port);
            serverSocketChannel.close();
            log.info("NettyServer关闭服务端口[{}:{}]监听", ((InetSocketAddress) serverSocketChannel.localAddress()).getHostString(), port);
            Set<Channel> socketChannelSet = GlobalCache.SP_SocketChannel_Map.get(port);
            Iterator<Channel> iterator = socketChannelSet.iterator();
            while (iterator.hasNext()) {
                Channel socketChannel = iterator.next();
                InetSocketAddress socketAddress = (InetSocketAddress) socketChannel.remoteAddress();
                socketChannel.close().addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("NettyServer关闭客户端[{}:{}]成功", socketAddress.getAddress().getHostAddress(), socketAddress.getPort());
                    } else {
                        log.error("NettyServer关闭客户端[{}:{}]失败, 失败信息:{}", socketAddress.getAddress().getHostAddress(), socketAddress.getPort(),future.cause().getMessage());
                    }
                });

            }
        }
    }

}
