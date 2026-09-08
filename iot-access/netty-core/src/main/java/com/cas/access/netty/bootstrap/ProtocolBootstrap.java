package com.cas.access.netty.bootstrap;

import com.cas.access.netty.protocol.*;
import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.server.NettyChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 协议模块启动初始化器。
 * 执行顺序：
 *  1. 注册所有内置 {@link ProtocolDecoderProvider}（Spring 扫到的 @Component 实现）
 *  2. 扫描并加载外部协议 jar（若 {@code auto-load-on-startup=true}）
 *  3. 从 DB 加载初始端口绑定（{@code port_protocol_binding} 表，仅登记内存映射，
 *     不触发端口监听，由 {@code NettyServerBootstrap} 完成绑定）
 * 必须在 {@code NettyServerBootstrap} 之前执行（{@link Order}(1)），
 * 这样端口绑定后客户端连接到来时 Registry 已就绪，Pipeline 装配不会失败。
 *
 * @author yjh_c
 */
@Slf4j
@Component
@Order(1)
public class ProtocolBootstrap implements CommandLineRunner {

    @Resource
    private List<ProtocolDecoderProvider> builtinProviders;

    @Resource
    private ProtocolRegistry registry;

    @Resource
    private ProtocolJarLoader jarLoader;

    @Resource
    private ProtocolProperties properties;

    /**
     * 端口绑定持久化回调。
     * 启动时从这里加载初始绑定（来自 DB），运行时新增/解绑也会回调写库。
     * required=false：DB 不可用时退化（无初始绑定，需手动通过控制台添加）。
     */
    @Autowired(required = false)
    private PortBindingStore bindingStore;

    /**
     * 协议配置持久化回调。
     * 启动时从数据库读取活跃的外部协议及其端口绑定。
     * required=false：DB 不可用时退化到目录扫描模式。
     */
    @Autowired(required = false)
    private ProtocolStore protocolStore;

    @Autowired(required = false)
    private ProtocolDbSync protocolDbSync;


    @Override
    public void run(String... args) {
        log.info("开始执行协议注册...");
        // 1. 注册内置 Provider
        for (ProtocolDecoderProvider p : builtinProviders) {
            LoadedProtocol lp = LoadedProtocol.builder()
                    .name(p.name())
                    .version(p.version())
                    .description(p.description())
                    .provider(p)
                    .classLoader(null)
                    .source("builtin")
                    .loadedAt(System.currentTimeMillis())
                    .active(true)
                    .build();
            registry.register(lp);
        }

        // 2. 加载外部协议 jar（优先从数据库读取）
        if (properties.isAutoLoadOnStartup()) {
            if (protocolStore != null) {
                    loadExternalProtocolsFromDb();
            } else {
                List<ProtocolJarLoader.LoadResult> results = jarLoader.scanAndLoad();
                int totalProviders = results.stream()
                        .mapToInt(r -> r.getProviderInfos().size())
                        .sum();
                log.info("外部协议扫描完成: 共 {} 个 jar，{} 个 Provider", results.size(), totalProviders);
            }
        }

        // 3. 从 DB 加载初始端口绑定（仅登记内存映射，不触发端口监听，由 NettyServerBootstrap 完成绑定）
        if (bindingStore == null) {
            log.warn("PortBindingStore 未注入（admin 模块可能未启用），无初始端口绑定");
            return;
        }
        Map<Integer, String> initialBindings = bindingStore.loadEnabledBindings();
        if (initialBindings.isEmpty()) {
            log.warn("DB 中无 enabled 的端口绑定，请前往控制台「端口管理」添加");
            return;
        }
        initialBindings.forEach((port, protoName) -> {
            if (registry.getProvider(protoName) != null) {
                // 只写内存，避免启动时无意义的 DB update
                registry.bindPortToProtocolInternal(port, protoName);
                log.info("初始端口绑定（来自 DB）: {} → 协议 {}", port, protoName);
            } else {
                log.warn("初始端口绑定失败，协议未注册: 端口 {} → 协议 {}", port, protoName);
            }
        });
    }

    private void loadExternalProtocolsFromDb() {
        Map<String, String> protocols = protocolStore.getActiveExternalProtocols();
        if (protocols.isEmpty()) {
            log.info("DB 中无活跃的外部协议");
            return;
        }

        for (Map.Entry<String, String> entry : protocols.entrySet()) {
            String protocolName = entry.getKey();
            String jarPath = entry.getValue();
            java.io.File jarFile = new java.io.File(jarPath);

            // 前置校验：协议必须至少有一个端口绑定，否则视为孤儿记录，标记无效并删除
            List<Integer> ports = protocolStore.getEnabledPortsByProtocol(protocolName);
            if (ports == null || ports.isEmpty()) {
                log.warn("协议[{}]无任何端口绑定，标记无效", protocolName);
                markInvalidAndDeleted(protocolName);
                continue;
            }

            // 本地 jar 不存在时，尝试从 DB jar_bytes 恢复到本地
            if (!jarFile.exists()) {
                log.warn("协议 jar 文件不存在，尝试从 DB 恢复: name={}, path={}", protocolName, jarPath);
                byte[] jarBytes = protocolStore.getExternalJarBytes(protocolName);
                if (jarBytes == null || jarBytes.length == 0) {
                    log.error("DB 无 jar_bytes，无法恢复，标记协议无效: name={}", protocolName);
                    markInvalidAndDeleted(protocolName);
                    continue;
                }
                try {
                    java.io.File jarDirFile = new java.io.File(properties.getJarDir());
                    if (!jarDirFile.exists() && !jarDirFile.mkdirs()) {
                        log.error("创建 jar 目录失败: {}", properties.getJarDir());
                        markInvalidAndDeleted(protocolName);
                        continue;
                    }
                    java.nio.file.Files.write(jarFile.toPath(), jarBytes);
                    log.info("从 DB 恢复协议 jar 成功: name={}, path={}, size={}KB",
                            protocolName, jarPath, jarBytes.length / 1024);
                } catch (Exception e) {
                    log.error("从 DB 恢复协议 jar 失败，标记协议无效: name={}, err={}", protocolName, e.getMessage());
                    markInvalidAndDeleted(protocolName);
                    continue;
                }
            }

            try {
                ProtocolJarLoader.LoadResult result = jarLoader.loadSingleJar(jarFile);
                if (!result.getProviderInfos().isEmpty()) {
                    log.info("从 DB 加载外部协议成功: name={}, jar={}", protocolName, jarPath);

                    for (Integer port : ports) {
                        registry.bindPortToProtocolInternal(port, protocolName);
                        log.info("外置协议端口绑定（来自 DB）: {} → 协议 {}", port, protocolName);
                    }
                } else {
                    log.warn("加载协议 jar 失败: name={}, jar={}, msg={}", protocolName, jarPath, result.getMessage());
                }
            } catch (Exception e) {
                log.error("加载外部协议异常: name={}, jar={}, err={}", protocolName, jarPath, e.getMessage());
            }
        }
    }

    /**
     * 标记协议为已卸载并清空 Provider 信息（jar 失效时调用）。
     * DB 不可用时（protocolDbSync 为 null）静默跳过。
     */
    private void markInvalidAndDeleted(String protocolName) {
        if (protocolDbSync == null) return;
        try {
            protocolDbSync.syncUnload(protocolName);
            protocolDbSync.clearProvider(protocolName);
        } catch (Exception e) {
            log.warn("标记协议无效失败: name={}, err={}", protocolName, e.getMessage());
        }
    }

}
