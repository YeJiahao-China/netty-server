package com.cas.access.netty;

import com.cas.access.netty.config.NettyServerConfiguration;
import com.cas.access.netty.handler.ServerLoggingHandler;
import com.cas.access.netty.server.NettyChannelInitializer;
import com.cas.access.netty.server.GlobalCache;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.HashSet;

/**
 * @author yjh_c
 * @描述: tcp服务启动类
 */
@SuppressWarnings({"AlibabaRemoveCommentedCode", "AlibabaCommentsMustBeJavadocFormat"})
@Component
@Slf4j
public class NettyServerBootstrap implements CommandLineRunner {

    public static ServerBootstrap serverBootstrap;
    @Resource
    private NettyChannelInitializer channelInitializer;

    @Resource
    private ServerLoggingHandler serverLoggingHandler;

    @Resource
    private NettyServerConfiguration nettyServerConfiguration;
    private EventLoopGroup boosGroup;
    private EventLoopGroup workGroup;

    private void start() {
        try {

            //Netty绑定IP和PORT设置
            int[] ports = nettyServerConfiguration.getPorts();

            //创建辅助工具类，用于服务器通道的一系列配置。
            //一个 Netty 应用通常由一个 Bootstrap 开始，主要作用是配置整个 Netty 程序，串联各个组件。
            //Bootstrap 类是客户端程序的启动引导类，ServerBootstrap 是服务端启动引导类。
            serverBootstrap = new ServerBootstrap();

            //服务端要建立两个group线程池：
            //一个是负责接收客户端连接的线程池
            //bossGroup就是parentGroup，是负责处理TCP/IP连接的
            boosGroup = new NioEventLoopGroup(ports.length);
            //一个负责处理数据传输的线程池
            //workerGroup就是childGroup,是负责处理Channel(通道)的I/O事件
            workGroup = new NioEventLoopGroup(20);

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

            group.handler(serverLoggingHandler);
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

            //绑定监听端口号，调用sync同步阻塞方法等待绑定操作完成，完成后返回ChannelFuture类似于JDK中Future
            for (int i = 0; i < ports.length; i++) {
                int finalI = i;
                ChannelFuture startFuture = serverBootstrap.bind(ports[finalI]).addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("NettyServer - 端口{}绑定成功", ports[finalI]);
                    } else {
                        log.error("NettyServer - 端口{}绑定失败:{}", ports[finalI], future.cause().getMessage());
                    }
                }).sync().addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("NettyServer - 启动{} - 监听端口{}", "成功", ports[finalI]);
                    } else {
                        log.error("NettyServer - 启动{} - 监听端口{}", "失败", ports[finalI]);
                    }
                });
                GlobalCache.SP_ServerChannel_Map.put(ports[finalI], startFuture.channel());
                GlobalCache.SP_SocketChannel_Map.put(ports[finalI],new HashSet<>());
//            //成功绑定到端口之后,给channel增加一个 管道关闭的监听器并同步阻塞,直到channel关闭,线程才会往下执行,结束进程。
//                channelFuture.channel().closeFuture().sync();
            }
        } catch (Exception e) {
            log.error("NettyServer启动失败: {}", e.getMessage());
        }
    }

    /**
     * 销毁资源
     */
    @PreDestroy
    public void destroy() {
        //关闭之前的操作
        //log.info("[NettyServer]-<即将关闭服务>");
    }

    @Override
    public void run(String... args) {
        start();
    }
}
