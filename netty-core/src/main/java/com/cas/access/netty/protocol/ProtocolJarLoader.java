package com.cas.access.netty.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
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
                    getClass().getClassLoader()
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

    /* ========== 强制关闭 ClassLoader（Windows jar 文件锁 workaround） ========== */

    /**
     * 强制关闭 URLClassLoader 及其内部所有 JarFile，确保 jar 文件句柄立即释放。
     *
     * <p>背景：{@code URLClassLoader.close()} 在某些场景下不保证释放所有 jar 文件句柄
     * （可能是 {@code loaders} 列表不完整，或 JarFile 被 JDK 内部缓存引用），
     * 导致 Windows 上卸载协议后 jar 文件仍被 JVM 锁定、无法删除/覆盖。
     *
     * <p>本方法用反射遍历 {@code URLClassPath} 内部的 {@code JarLoader}，逐个关闭
     * {@code JarFile} 并断开引用，确保文件句柄立即释放。
     * 这是 Tomcat {@code WebappClassLoader} / JBoss 等热部署框架的通用 workaround。
     *
     * <p><b>注意</b>：JDK 9+ 模块系统会阻止反射访问私有字段，需要添加 JVM 参数：
     * <pre>
     * --add-opens java.base/java.net=ALL-UNNAMED
     * --add-opens java.base/jdk.internal.loader=ALL-UNNAMED
     * </pre>
     * 如果未添加参数，反射会失败并打印 warn，退化为普通 {@code close()}。
     *
     * @param cl 要关闭的 ClassLoader（非 URLClassLoader 时直接返回）
     */
    public static void forceCloseClassLoader(ClassLoader cl) {
        if (cl == null) {
            return;
        }

        // 1. 先调用 URLClassLoader.close()（关闭已知的 loaders）
        if (cl instanceof URLClassLoader) {
            try {
                ((URLClassLoader) cl).close();
            } catch (IOException e) {
                log.warn("URLClassLoader.close() 失败: {}", e.getMessage());
            }
        }

        // 2. 反射清理 URLClassPath 内部的所有 JarFile
        try {
            Field ucpField = URLClassLoader.class.getDeclaredField("ucp");
            ucpField.setAccessible(true);
            Object ucp = ucpField.get(cl);
            if (ucp == null) {
                return;
            }

            int closedCount = 0;
            // 关闭 loaders 列表中的 JarFile
            closedCount += closeJarFilesInField(ucp, "loaders");
            // 关闭 lmap 中的 JarFile
            closedCount += closeJarFilesInField(ucp, "lmap");

            if (closedCount > 0) {
                log.info("反射强制关闭 {} 个 JarFile（ClassLoader={}）", closedCount, cl);
            }
        } catch (RuntimeException e) {
            log.warn("反射清理 JarFile 被 JDK 模块系统阻止，请在 JVM 参数中添加: "
                    + "--add-opens java.base/java.net=ALL-UNNAMED "
                    + "--add-opens java.base/jdk.internal.loader=ALL-UNNAMED "
                    + "（当前退化为普通 close，jar 文件可能仍被锁定）");
        } catch (Exception e) {
            log.warn("反射清理 JarFile 失败: {}", e.getMessage());
        }
    }

    /**
     * 反射获取 URLClassPath 的指定字段（loaders / lmap），遍历其中所有 Loader，
     * 关闭其内部的 JarFile。
     *
     * @return 成功关闭的 JarFile 数量
     */
    @SuppressWarnings("unchecked")
    private static int closeJarFilesInField(Object ucp, String fieldName) {
        try {
            Field field = findField(ucp.getClass(), fieldName);
            if (field == null) {
                return 0;
            }
            field.setAccessible(true);
            Object obj = field.get(ucp);
            if (obj == null) {
                return 0;
            }

            Iterable<Object> iterable;
            if (obj instanceof List) {
                iterable = (Iterable<Object>) obj;
            } else if (obj instanceof Map) {
                iterable = ((Map<?, Object>) obj).values();
            } else {
                return 0;
            }

            int count = 0;
            for (Object loader : iterable) {
                count += closeLoaderJarFile(loader);
            }
            return count;
        } catch (Exception e) {
            // 字段不存在或访问失败，跳过
            return 0;
        }
    }

    /**
     * 关闭单个 JarLoader 内部的 JarFile，并断开引用帮助 GC。
     */
    private static int closeLoaderJarFile(Object loader) {
        try {
            Field jarField = findField(loader.getClass(), "jar");
            if (jarField == null) {
                return 0;
            }
            jarField.setAccessible(true);
            Object jar = jarField.get(loader);
            if (jar instanceof JarFile) {
                JarFile jarFile = (JarFile) jar;
                String name = jarFile.getName();
                // close() 是幂等的，已关闭再调不会报错；直接关闭确保文件句柄释放
                jarFile.close();
                log.info("反射强制关闭 JarFile: {}", name);
                jarField.set(loader, null); // 断开引用，帮助 GC 回收 ClassLoader
                return 1;
            }
        } catch (Exception e) {
            log.warn("关闭 Loader JarFile 失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 沿继承链查找字段（JDK 内部类可能有继承层级）。
     */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
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
