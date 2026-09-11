package com.cas.access.netty.util;

import com.cas.access.netty.bootstrap.NettyServerBootstrap;
import com.cas.access.netty.server.GlobalCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author JHYe
 * @date 2023/10/27
 */
@Slf4j
public class NettyServerUtil {

    /**
     * 端口关闭专用虚拟线程池：按需创建虚拟线程、不阻止 JVM 退出。
     * 仅用于 {@link #closeListenAll} 多端口并行关闭场景（协议卸载时协议可能绑定多个端口）。
     */
    private static final ExecutorService CLOSE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();


    public static String getServerIp(ChannelHandlerContext ctx) {
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        return localAddress.getAddress().getHostAddress();
    }

    public static int getServerPort(ChannelHandlerContext ctx) {
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        return localAddress.getPort();
    }

    public static String getClientIp(ChannelHandlerContext ctx) {
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        return clientAddress.getAddress().getHostAddress();
    }

    public static int getClientPort(ChannelHandlerContext ctx) {
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        return clientAddress.getPort();
    }

    /**
     * 同步阻塞式监听端口 返回结果
     * @param port 端口
     * @return 结果
     */
    public static boolean bindPort(int port) {
        try {
            // 1. bind() 发起异步绑定，sync() 阻塞当前线程直到绑定操作彻底完成
            ChannelFuture startFuture = NettyServerBootstrap.serverBootstrap.bind("0.0.0.0", port).sync();

            // 2. 此时 Future 已经 100% 完成，直接判断其状态，不存在竞态条件
            if (startFuture.isSuccess()) {
                log.info("IotAccessServer添加端口[{}]成功", port);
                GlobalCache.bindServerChannel(port, startFuture.channel());
                GlobalCache.registerPort(port);
                return true;
            } else {
                log.error("IotAccessServer添加端口[{}]失败", port, startFuture.cause());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态
            log.error("IotAccessServer添加端口[{}]线程被中断", port, e);
            return false;
        } catch (Exception e) {
            log.error("IotAccessServer添加端口[{}]发生异常", port, e);
            return false;
        }
    }


//    /**
//     * 新增服务监听端口
//     */
//    public static boolean bindPort(int port) {
//        try {
//            AtomicBoolean result = new AtomicBoolean(false);
//            ChannelFuture startFuture = NettyServerBootstrap.serverBootstrap.bind("0.0.0.0", port).addListener(future -> {
//                if (future.isSuccess()) {
//                    log.info("IotAccessServer添加端口[{}]成功", port);
//                    result.set(true);
//                } else {
//                    log.error("IotAccessServer添加端口[{}]失败:{}", port, future.cause().getMessage());
//                }
//            }).sync();
//
////                    .addListener(future -> {
////                if (future.isSuccess()) {
////                    log.info("NettyServer - Add Port{}{}", port, "Success");
////                } else {
////                    log.error("NettyServer - Add Port{}{}", port, "Fail");
////                }
////            });
//            if (result.get()) {
//                GlobalCache.bindServerChannel(port, startFuture.channel());
//                GlobalCache.registerPort(port);
//            }
//            return result.get();
//        } catch (Exception e) {
//            return false;
//        }
//    }

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
        // 注意：close() 之后 localAddress() 会返回 null，日志统一用 port 参数，避免 NPE
        log.info("NettyServer关闭监听端口[{}]", port);

        Set<Channel> socketChannelSet = GlobalCache.unregisterPort(port);
        if (socketChannelSet == null || socketChannelSet.isEmpty()) {
            return;
        }
        for (Channel socketChannel : socketChannelSet) {
            // close 前先安全格式化地址：listener 回调时连接可能已关闭，remoteAddress() 会返回 null
            String remote = formatRemoteAddress(socketChannel);
            socketChannel.close().addListener(future -> {
                if (future.isSuccess()) {
                    log.info("NettyServer关闭客户端[{}]成功", remote);
                } else {
                    log.error("NettyServer关闭客户端[{}]失败, 失败信息:{}",
                            remote,
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                }
            });
        }
    }


    /**
     * 关闭指定端口的监听及所有活跃连接，并同步等待 Pipeline 完全清理
     *
     * @param port           端口号
     * @param timeoutSeconds 等待超时时间（秒），防止恶意客户端阻塞卸载流程
     * @return true=所有连接在超时前正常关闭, false=存在超时或异常
     */
    public static boolean closeListen(int port, int timeoutSeconds) {
        Channel serverSocketChannel = GlobalCache.removeServerChannel(port);
        if (serverSocketChannel == null) {
            log.warn("监听端口[{}]未找到对应的ServerSocketChannel，跳过关闭", port);
            return true;
        }

        // 1. 关闭服务端监听端口（阻止新连接接入）
        try {
            serverSocketChannel.close().sync();
            // 注意：close() 之后 localAddress() 返回 null，日志统一用 port 参数
            log.info("NettyServer关闭监听端口[{}]", port);
        } catch (Exception e) {
            log.error("关闭服务端口[{}]监听异常: {}", port, e.getMessage(), e);
        }

        // 2. 收集所有客户端连接的 CloseFuture
        Set<Channel> socketChannelSet = GlobalCache.unregisterPort(port);
        if (socketChannelSet == null || socketChannelSet.isEmpty()) {
            return true;
        }
        log.info("NettyServer即将关闭服务端口[{}]上{}个客户端连接", port, socketChannelSet.size());

        List<ChannelFuture> closeFutures = new ArrayList<>(socketChannelSet.size());
        for (Channel socketChannel : socketChannelSet) {
            // 移除缓存的连接
            GlobalCache.removeConnection(socketChannel.id());
            if (socketChannel.isActive()) {
                closeFutures.add(socketChannel.close());
            }
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        int processedCount = 0;
        int failCount = 0;

        for (ChannelFuture future : closeFutures) {
            long remaining = deadline - System.currentTimeMillis();
            // 剩余时间可能为负数，但 await 要求非负，因此取最大值 0
            long waitMillis = Math.max(0, remaining);

            try {
                boolean completed = future.await(waitMillis, TimeUnit.MILLISECONDS);
                if (!completed) {
                    // 超时。注意：channel 关闭后 remoteAddress() 返回 null，必须用安全格式化，否则 NPE 会抛穿整个卸载流程
                    log.warn("关闭客户端[{}]超时，接收端可能正在读取数据",
                            formatRemoteAddress(future.channel()));
                    failCount++;
                } else if (!future.isSuccess()) {
                    // 关闭失败（异常）
                    log.error("关闭客户端[{}]失败: {}",
                            formatRemoteAddress(future.channel()),
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    failCount++;
                } else {
                    // 关闭成功
                    log.info("NettyServer关闭客户端[{}]成功",
                            formatRemoteAddress(future.channel()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待端口[{}]连接关闭被中断", port);
                failCount = closeFutures.size() - processedCount; // 剩下未处理的都算失败
                break;
            }
            processedCount++;
        }

        // 最终统计
        if (failCount > 0) {
            log.warn("监听端口[{}]连接关闭完成，共{}个连接，其中{}个失败/超时", port, closeFutures.size(), failCount);
            return false;
        } else {
            log.info("监听端口[{}]所有连接已成功关闭", port);
            return true;
        }
    }

    /**
     * 并行关闭多个端口的监听及所有活跃连接（协议卸载场景：一个协议可能绑定多个端口）。
     *
     * <p>每个端口在独立虚拟线程中执行 {@link #closeListen(int, int)}，
     * 总耗时约等于最慢的一个端口，而非各端口串行累加——
     * 保证节点卸载总耗时可预期（约 timeoutSeconds），不会被 cluster-admin
     * 的广播读超时误判为节点失败后触发无意义的重复分发。</p>
     *
     * @param ports          端口列表
     * @param timeoutSeconds 每个端口的关闭等待超时（秒）
     * @return true=所有端口全部正常关闭；false=任一端口存在超时或异常
     */
    public static boolean closeListenAll(List<Integer> ports, int timeoutSeconds) {
        if (ports == null || ports.isEmpty()) {
            return true;
        }
        if (ports.size() == 1) {
            return closeListen(ports.getFirst(), timeoutSeconds);
        }
        log.info("并行关闭 {} 个端口监听: {}", ports.size(), ports);
        List<CompletableFuture<Boolean>> futures = ports.stream()
                .map(port -> CompletableFuture.supplyAsync(
                        () -> closeListen(port, timeoutSeconds), CLOSE_EXECUTOR))
                .toList();
        boolean allSuccess = true;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                // closeListen 自身受 timeoutSeconds 约束，等待时多留 5 秒冗余应对线程调度
                allSuccess &= future.get(timeoutSeconds + 5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待端口关闭被中断: {}", e.getMessage());
                allSuccess = false;
            } catch (Exception e) {
                log.warn("等待端口关闭异常: {}", e.getMessage());
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 扫尾清理端口残留连接（协议卸载竞态兜底）。
     *
     * <p>背景：即使卸载流程先清端口映射再关监听，仍存在极小概率的竞态窗口——
     * 关闭监听前最后一瞬已 accept、但 channelActive 尚未执行的连接，会在端口
     * 连接集合被 {@link #closeListen(int, int)} 取走后通过
     * {@code computeIfAbsent} 重建集合并逃过踢除（漏网连接）。其 pipeline 持有
     * 已卸载 Provider 的类引用，会导致 ClassLoader 无法回收（Metaspace 泄漏）
     * 且连接继续处理数据。</p>
     *
     * <p>本方法在「监听已关闭、端口映射已清理」之后调用：此时不会再有新连接进入，
     * 再次取走集合并关闭其中所有残余连接即可幂等收口。</p>
     *
     * @param ports 端口列表
     * @return 实际清理的漏网连接总数（正常情况下为 0，大于 0 说明发生了竞态）
     */
    public static int sweepPortConnections(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return 0;
        }
        int swept = 0;
        for (int port : ports) {
            Set<Channel> remain = GlobalCache.unregisterPort(port);
            if (remain == null || remain.isEmpty()) {
                continue;
            }
            for (Channel ch : remain) {
                // 异步 close 即可：close 会触发 channelInactive → 清理 CONNECTION_MAP，
                // 并销毁 pipeline 释放对 Provider 类的引用，ClassLoader 随后可被 GC
                if (ch.isActive()) {
                    log.info("扫尾关闭端口[{}]漏网连接: {}", port, formatRemoteAddress(ch));
                    ch.close();
                    swept++;
                }
            }
        }
        return swept;
    }

    /**
     * 安全格式化客户端地址。
     *
     * <p>channel 关闭后 {@code remoteAddress()} 返回 null，直接强转取
     * {@code getAddress().getHostAddress()} 会 NPE（unresolved 地址同理），
     * 该 NPE 会抛穿整个卸载流程造成半卸载状态，此处降级显示 channelId。</p>
     */
    private static String formatRemoteAddress(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            // getHostString() 对 unresolved 地址也安全（不触发 DNS 反查、不返回 null）
            return addr.getHostString() + ":" + addr.getPort();
        }
        return "channel-" + channel.id().asShortText();
    }

}
