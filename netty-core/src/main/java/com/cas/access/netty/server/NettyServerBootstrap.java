package com.cas.access.netty.server;

import com.cas.access.netty.protocol.ProtocolBootstrap;
import com.cas.access.netty.protocol.ProtocolRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Map;

/**
 * TCP 服务启动类。
 *
 * <p>执行顺序晚于 {@link ProtocolBootstrap}（{@link Order}(2)），
 * 这样启动时 ProtocolRegistry 的端口绑定已从 DB 装载就绪，
 * 直接根据 {@link ProtocolRegistry#getAllBindings()} 的端口列表绑定监听。
 *
 * @author yjh_c
 */
@Component
@Slf4j
@Order(2)
public class NettyServerBootstrap implements CommandLineRunner {

    public static ServerBootstrap serverBootstrap;
    @Resource
    private NettyChannelInitializer channelInitializer;
    @Resource
    private ProtocolRegistry registry;
    private EventLoopGroup boosGroup;
    private EventLoopGroup workGroup;

    private void start() {
        try {
            log.info("NettyServer - 开始启动中...");
            // 端口列表来自 ProtocolRegistry（DB 中 enabled 的端口绑定）
            Map<Integer, String> bindings = registry.getAllBindings();
            if (bindings.isEmpty()) {
                log.warn("NettyServer - 无端口绑定，跳过监听启动（请前往控制台「端口管理」添加）");
                return;
            }
            int portCount = bindings.size();
            log.info("NettyServer - 端口列表: {}", bindings);
            //创建辅助工具类，用于服务器通道的一系列配置。
            //一个 Netty 应用通常由一个 Bootstrap 开始，主要作用是配置整个 Netty 程序，串联各个组件。
            //Bootstrap 类是客户端程序的启动引导类，ServerBootstrap 是服务端启动引导类。
            serverBootstrap = new ServerBootstrap();

            //服务端要建立两个group线程池：
            //一个是负责接收客户端连接的线程池
            //bossGroup就是parentGroup，是负责处理TCP/IP连接的
//            boosGroup = new NioEventLoopGroup(portCount);
            boosGroup = new NioEventLoopGroup(portCount, new DefaultThreadFactory("boss"));
            //一个负责处理数据传输的线程池
            //workerGroup就是childGroup,是负责处理Channel(通道)的I/O事件
            workGroup = new NioEventLoopGroup(8,new DefaultThreadFactory("worker"));

            // 使用ServerBootstrap引导类绑定线程池
            ServerBootstrap group = serverBootstrap.group(boosGroup, workGroup);

            // 本channel使用非阻塞NIO方式设置channel
            group.channel(NioServerSocketChannel.class);

            // 设置channel（通道）的option（选项）。
            // ChannelOption.SO_BACKLOG设置主要是针对boss线程组。
            // NettyServerConfig.ChannelOption_SO_BACKLOG设置TCP可连接队列数。服务端接受连接的队列长度，如果队列已满，客户端连接将被拒绝。
            group.option(ChannelOption.SO_BACKLOG, 1024);
            // 允许重复使用本地地址和端口
            group.option(ChannelOption.SO_REUSEADDR, true);
            //childOption设置主要是针对worker线程组
            // 保持长连接，可以在设置的读写超时时间范围内保持连接
            group.childOption(ChannelOption.SO_KEEPALIVE, true);
            // 非延迟，有数据立刻发送
            group.childOption(ChannelOption.TCP_NODELAY, true);
            // new ChannelInitializer<SocketChannel>() 用途当一个新的连接被接受时，
            // 一个新的子 Channel 将会被创建，而 ChannelInitializer 将会把一个
            // NettyServerNettyHandlerBiz 的实例添加到该 Channel 的 ChannelPipeline 中。
            // 绑定客户端连接时候触发操作
            group.childHandler(channelInitializer);
            for (Integer port : bindings.keySet()) {
//                ChannelFuture sync = serverBootstrap.bind(port).sync();
//                if (sync.isSuccess()) {
//                    log.info("NettyServer - 端口:{} 绑定成功（协议: {}）", port, bindings.get(port));
//                } else {
//                    log.error("NettyServer - 端口:{} 绑定失败:{}", port, sync.cause().getMessage());
//                }
                ChannelFuture sync = serverBootstrap.bind(port).addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("NettyServer - 端口:{} 绑定成功（协议: {}）", port, bindings.get(port));
                    } else {
                        log.error("NettyServer - 端口:{} 绑定失败:{}", port, future.cause().getMessage());
                    }
                }).sync();
                GlobalCache.ServerPort_ServerSocketChannel_Map.put(port, sync.channel());
                GlobalCache.ServerPost_SocketChannelSet_Map.put(port, new HashSet<>());
            }
        } catch (Exception e) {
            log.error("NettyServer 启动失败: {}", e.getMessage());
            destroy();
        }
    }

    /**
     * 销毁资源
     */
    @PreDestroy
    public void destroy() {
        //关闭之前的操作
        log.info("[NettyServer]-<关闭服务>");
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workGroup != null) workGroup.shutdownGracefully();
    }

    @Override
    public void run(String... args) {
        start();
    }
}
