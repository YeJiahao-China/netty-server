package com.cas.access.netty.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * 协议 jar 动态加载器。
 *
 * 工作流程：
 *  1. 扫描 {@code ${netty.server.protocol.jar-dir}} 下所有 *.jar
 *  2. 每个 jar 创建独立 {@link URLClassLoader}（parent = 当前线程 ContextClassLoader，
 *     确保外部 jar 能引用到主项目的 netty-all 与 ProtocolDecoderProvider 接口）
 *  3. 通过 {@link ServiceLoader} 加载 {@link ProtocolDecoderProvider} 实现类
 *  4. 包装为 {@link LoadedProtocol} 注册到 {@link ProtocolRegistry}
 *  5. 若 jar 中无任何 Provider 实现，立即 close ClassLoader 避免泄漏
 *
 * 重名协议策略：
 *  - 由 {@link ProtocolRegistry#register} 内部处理：先卸载旧的（关旧 ClassLoader），再注册新的
 *
 * @author yjh_c
 */
@Slf4j
@Component
public class ProtocolJarLoader {

    @Resource
    private ProtocolRegistry registry;

    @Resource
    private ProtocolProperties properties;

    /**
     * 全量重扫：重新加载目录下所有 jar。不动内置协议。
     *
     * @return 每个 jar 的加载结果列表，便于 HTTP 接口反馈
     */
    public List<LoadResult> scanAndLoad() {
        List<LoadResult> results = new ArrayList<>();
        Path dir = Paths.get(properties.getJarDir());
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                log.warn("创建协议目录失败: {}", e.getMessage());
            }
            log.warn("协议目录不存在或为空: {}", dir.toAbsolutePath());
            return results;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .forEach(p -> results.add(loadSingleJar(p.toFile())));
        } catch (Exception e) {
            log.error("扫描协议目录失败: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * 加载单个 jar 并注册到 Registry（供运行时上传 / 全量扫描共用）。
     * 由 {@link ProtocolRegistry#register} 内部处理同名协议的热替换。
     */
    public LoadResult loadSingleJar(File jarFile) {
        return doLoad(jarFile);
    }

    /**
     * 临时加载校验：取出 Provider 真实 name()，<b>不注册到 Registry</b>。
     * 调用方拿到结果后自行决定是否 register。
     * 本方法会在 finally 中关闭临时 ClassLoader。
     */
    public ProbeResult probe(File jarFile) {
        return doProbe(jarFile);
    }

    private LoadResult doLoad(File jarFile) {
        LoadResult result = new LoadResult(jarFile.getAbsolutePath());
        try {
            URLClassLoader cl = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    getClass().getClassLoader()
//                    Thread.currentThread().getContextClassLoader()
            );
            ServiceLoader<ProtocolDecoderProvider> sl =
                    ServiceLoader.load(ProtocolDecoderProvider.class, cl);

            int count = 0;
            for (ProtocolDecoderProvider provider : sl) {
                try {
                    LoadedProtocol lp = LoadedProtocol.builder()
                            .name(provider.name())
                            .version(provider.version())
                            .description(provider.description())
                            .provider(provider)
                            .classLoader(cl)
                            .source(jarFile.getAbsolutePath())
                            .loadedAt(System.currentTimeMillis())
                            .active(true)
                            .build();
                    registry.register(lp);
                    result.addProvider(provider.name(), provider.version(), "OK");
                    count++;
                } catch (Throwable t) {
                    result.addProvider(provider.getClass().getName(), provider.version(), "FAIL: " + t.getMessage());
                    log.error("注册协议 Provider 失败: jar={}, provider={}", jarFile.getName(), provider.getClass(), t);
                }
            }
            if (count == 0) {
                cl.close();
                result.setMessage("未发现任何 ProtocolDecoderProvider 实现");
                log.warn("jar 中未发现 Provider 实现，已关闭 ClassLoader: {}", jarFile.getAbsolutePath());
            }
        } catch (Throwable t) {
            result.setMessage("加载失败: " + t.getMessage());
            log.error("加载协议 jar 失败: {}", jarFile.getAbsolutePath(), t);
        }
        return result;
    }

    /** 单次加载结果描述，用于 HTTP 返回 */
    @lombok.Getter
    public static class LoadResult {

        private final String jarPath;
        private final List<String> providerInfos = new ArrayList<>();
        private String message = "OK";

        public LoadResult(String jarPath) {
            this.jarPath = jarPath;
        }

        public void addProvider(String name, String version, String status) {
            providerInfos.add(name + ":" + version + " -> " + status);
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * 临时加载实现：仅用独立 URLClassLoader 读取 Provider 元数据，不注册到 Registry。
     * 完成后立即 close ClassLoader，避免泄漏。
     */
    private ProbeResult doProbe(File jarFile) {
        ProbeResult r = new ProbeResult();
        URLClassLoader cl = null;
        try {
            cl = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );
            ServiceLoader<ProtocolDecoderProvider> sl =
                    ServiceLoader.load(ProtocolDecoderProvider.class, cl);
            for (ProtocolDecoderProvider p : sl) {
                r.setSuccess(true);
                r.setProviderName(p.name());
                r.setProviderVersion(p.version());
                r.setDescription(p.description());
                return r;
            }
            r.setErrorMessage("jar 中未发现任何 ProtocolDecoderProvider 实现");
        } catch (Throwable t) {
            r.setErrorMessage("加载失败: " + t.getMessage());
            log.error("临时加载 jar 失败: {}", jarFile.getAbsolutePath(), t);
        } finally {
            if (cl != null) {
                try { cl.close(); } catch (Exception ignored) {}
            }
        }
        return r;
    }

    /** 临时加载校验结果（用于上传时校验用户输入协议名是否与 jar 内一致） */
    @lombok.Data
    public static class ProbeResult {
        private boolean success;
        private String providerName;
        private String providerVersion;
        private String description;
        private String errorMessage;
    }
}
