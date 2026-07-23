package com.cas.access.netty.util;

import com.cas.access.netty.server.NettyServerBootstrap;
import com.cas.access.netty.server.GlobalCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
                    log.info("NettyServer - 添加端口{} 成功", port);
                } else {
                    log.error("NettyServer - 添加端口{} 失败:{}", port, future.cause().getMessage());
                }
            }).sync();
//                    .addListener(future -> {
//                if (future.isSuccess()) {
//                    log.info("NettyServer - Add Port{}{}", port, "Success");
//                } else {
//                    log.error("NettyServer - Add Port{}{}", port, "Fail");
//                }
//            });
            GlobalCache.bindServerChannel(port, startFuture.channel());
            GlobalCache.registerPort(port);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 关闭端口监听（异步，不等待连接关闭）。
     *
     * <p>与 {@link #closeListen(int, int)} 的区别：不同步等待客户端连接关闭，
     * 适用于对关闭时序无严格要求的场景（如手动解绑端口）。
     * 两个版本都会清理 {@link GlobalCache} 中的 Map 条目，避免残留引用。
     *
     * @param port 端口号
     */
    public static void closeListen(int port) {
        Channel serverSocketChannel = GlobalCache.removeServerChannel(port);
        if (serverSocketChannel == null) {
            return;
        }
        serverSocketChannel.close();
        log.info("NettyServer关闭服务端口[{}:{}]监听",
                ((InetSocketAddress) serverSocketChannel.localAddress()).getHostString(), port);

        Set<Channel> socketChannelSet = GlobalCache.unregisterPort(port);
        if (socketChannelSet == null || socketChannelSet.isEmpty()) {
            return;
        }
        for (Channel socketChannel : socketChannelSet) {
            InetSocketAddress socketAddress = (InetSocketAddress) socketChannel.remoteAddress();
            socketChannel.close().addListener(future -> {
                if (future.isSuccess()) {
                    log.info("NettyServer关闭客户端[{}:{}]成功",
                            socketAddress.getAddress().getHostAddress(), socketAddress.getPort());
                } else {
                    log.error("NettyServer关闭客户端[{}:{}]失败, 失败信息:{}",
                            socketAddress.getAddress().getHostAddress(), socketAddress.getPort(),
                            future.cause().getMessage());
                }
            });
        }
    }



    /**
     * 关闭指定端口的监听及所有活跃连接，并同步等待 Pipeline 完全清理
     * @param port 端口号
     * @param timeoutSeconds 等待超时时间（秒），防止恶意客户端阻塞卸载流程
     * @return true=所有连接在超时前正常关闭, false=存在超时或异常
     */
    public static boolean closeListen(int port, int timeoutSeconds) {
        Channel serverSocketChannel = GlobalCache.removeServerChannel(port);
        if (serverSocketChannel == null) {
            log.warn("端口[{}]未找到对应的ServerChannel，跳过关闭", port);
            return true;
        }

        // 1. 关闭服务端监听端口（阻止新连接接入）
        try {
            serverSocketChannel.close().sync();
            log.info("NettyServer关闭服务端口[{}:{}]监听",
                    ((InetSocketAddress) serverSocketChannel.localAddress()).getHostString(), port);
        } catch (Exception e) {
            log.error("关闭服务端口[{}]监听异常: {}", port, e.getMessage(), e);
        }

        // 2. 收集所有客户端连接的 CloseFuture
        Set<Channel> socketChannelSet = GlobalCache.unregisterPort(port);
        if (socketChannelSet == null || socketChannelSet.isEmpty()) {
            return true;
        }

        List<ChannelFuture> closeFutures = new ArrayList<>(socketChannelSet.size());
        for (Channel socketChannel : socketChannelSet) {
            // 移除缓存的连接
            GlobalCache.removeConnection(socketChannel.id());
            if (socketChannel.isActive()) {
                closeFutures.add(socketChannel.close());
            }
        }

        // 3. 💥 核心改造：同步等待所有连接完全关闭 + Pipeline Handler 被移除
        boolean allSuccess = true;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        for (ChannelFuture future : closeFutures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("端口[{}]连接关闭超时，剩余{}个连接未完全关闭", port,
                        closeFutures.size() - closeFutures.indexOf(future));
                allSuccess = false;
                break;
            }

            try {
                if (!future.await(remaining, TimeUnit.MILLISECONDS)) {
                    log.warn("端口[{}]某个连接关闭超时", port);
                    allSuccess = false;
                } else if (!future.isSuccess()) {
                    InetSocketAddress addr = (InetSocketAddress) future.channel().remoteAddress();
                    log.error("关闭客户端[{}:{}]失败: {}",
                            addr.getAddress().getHostAddress(), addr.getPort(),
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    allSuccess = false;
                } else {
                    InetSocketAddress addr = (InetSocketAddress) future.channel().remoteAddress();
                    log.info("NettyServer关闭客户端[{}:{}]成功",
                            addr.getAddress().getHostAddress(), addr.getPort());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待端口[{}]连接关闭被中断", port);
                allSuccess = false;
                break;
            }
        }

        return allSuccess;
    }

}
