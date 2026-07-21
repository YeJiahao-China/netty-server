package com.cas.access.netty.api;

import com.cas.access.netty.entity.PortBinding;
import com.cas.access.netty.entity.ProtocolJarRegistry;
import com.cas.access.netty.protocol.LoadedProtocol;
import com.cas.access.netty.protocol.ProtocolJarLoader;
import com.cas.access.netty.protocol.ProtocolProperties;
import com.cas.access.netty.protocol.ProtocolRegistry;
import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.service.PortBindingService;
import com.cas.access.netty.service.ProtocolJarRegistryService;
import com.cas.access.netty.util.NettyServerUtil;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协议管理 HTTP 接口（端口 2310）。
 * 卸载安全策略：
 * - DELETE /protocols/{name} 时，Registry 会自动关闭该协议绑定的端口监听 + 踢连接
 * - 也可手动先 DELETE /protocols/bind/{port} 解绑端口，再 DELETE /protocols/{name}
 *
 * @author yjh_c
 */
@Slf4j
@RestController
@RequestMapping("/protocols")
public class ProtocolController {

    @Resource
    private ProtocolJarLoader jarLoader;

    @Resource
    private ProtocolRegistry registry;

    @Resource
    private ProtocolProperties properties;

    @Resource
    private ProtocolJarRegistryService protocolJarRegistryService;

    @Resource
    private PortBindingService portBindingService;

    /**
     * 上传协议 jar 一键热加载。
     *
     * <p>流程（两阶段：先校验后覆盖）：
     * <ol>
     *   <li>基础校验：文件非空、后缀 .jar、端口范围 1024-65535</li>
     *   <li>端口冲突预检：端口已被其他协议占用 → 拒绝</li>
     *   <li>写入临时文件 {@code protocols/.upload/xxx.jar.tmp}</li>
     *   <li>{@link ProtocolJarLoader#probe(File)} 临时加载，取出 Provider 真实 name()</li>
     *   <li>校验 name 与用户输入一致；不一致 → 删临时文件 + 返回失败</li>
     *   <li>rename 到 {@code protocols/xxx.jar}（覆盖旧版本）</li>
     *   <li>{@link ProtocolJarLoader#loadSingleJar(File)} 正式注册（Registry 自动热替换）</li>
     *   <li>{@link ProtocolRegistry#bindPortToProtocol} 写内存 + DB</li>
     *   <li>{@link NettyServerUtil#bindPort} 启动 Netty 端口监听</li>
     * </ol>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("protocolName") String protocolName,
            @RequestParam("port") int port) {

        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            return fail("文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".jar")) {
            return fail("仅支持 .jar 文件");
        }
        if (protocolName == null || protocolName.trim().isEmpty()) {
            return fail("协议名不能为空");
        }
        if (port < 1024 || port > 65535) {
            return fail("端口范围必须在 1024-65535");
        }

        // 2. 端口冲突预检
        String existing = registry.getProtocolNameByPort(port);
        if (existing != null) {
            return fail("端口 " + port + " 已被协议[" + existing + "]占用，请先解绑");
        }

        Path tempFile = null;
        Path probeCopy = null;
        try {
            // 3. 写入临时文件（必须用绝对路径 + Files.copy，避免 Tomcat transferTo
            //    把相对路径错误拼到 %TEMP%/tomcat.xxx/work/... 下）
            Path uploadDir = Paths.get(properties.getJarDir(), ".upload").toAbsolutePath();
            Files.createDirectories(uploadDir);
            tempFile = uploadDir.resolve(filename + ".tmp");
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 4. 临时加载校验（关键：必须加载「副本」而不是 tempFile 本身！）
            //    原因：URLClassLoader.close() 在 Java 8 + Windows 上不保证立即释放 jar 文件句柄，
            //    底层 JarFile 释放依赖 GC（JDK-8054458）。如果直接 probe(tempFile)，
            //    probe 完成后 tempFile 仍被锁，后续 Files.move 会失败。
            //    解决：把 tempFile 复制一份到 JVM 临时目录用于 probe，tempFile 本体保持纯净。
            probeCopy = Files.createTempFile("protocol-probe-", ".jar");
            Files.copy(tempFile, probeCopy, StandardCopyOption.REPLACE_EXISTING);
            ProtocolJarLoader.ProbeResult probe = jarLoader.probe(probeCopy.toFile());
            if (!probe.isSuccess()) {
                Files.deleteIfExists(tempFile);
                return fail("jar 加载失败: " + probe.getErrorMessage());
            }
            // 5. 协议名一致性校验
            if (!protocolName.equals(probe.getProviderName())) {
                Files.deleteIfExists(tempFile);
                return fail("协议名不匹配：jar 内实际为 [" + probe.getProviderName()
                        + "]，与输入 [" + protocolName + "] 不符");
            }
            // probe 完成，立即尝试删除副本（删除失败也无妨，JVM 退出时 %TEMP% 自动清理）
            try {
                Files.deleteIfExists(probeCopy);
            } catch (Exception ignored) {
            }
            probeCopy = null;

            // 6. 释放旧 jar 文件锁：旧版本如果已注册，URLClassLoader 仍持有 jar 文件句柄
            //    （Windows 上会锁死文件导致无法覆盖）。先关闭旧 ClassLoader 释放句柄，
            //    protocols 条目和端口绑定不动，旧连接的 Handler 实例仍可工作。
            registry.closeClassLoaderForUpgrade(protocolName);

            // 7. rename 覆盖正式文件（绝对路径）
            Path finalFile = Paths.get(properties.getJarDir(), filename).toAbsolutePath();
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile = null; // 已 move，不再清理

            // 8. 正式注册（Registry 内部 unregisterInternal 会 destroy 旧 Provider，
            //    但旧 ClassLoader 已在第 6 步关闭，这里 close 是 no-op）
            jarLoader.loadSingleJar(finalFile.toFile());

            // 9. 绑定端口（写 DB） + 10. 启动监听
            registry.bindPortToProtocol(port, protocolName);
            NettyServerUtil.bindPort(port);

            log.info("协议[{}]上传成功，已绑定端口[{}]并启动监听", protocolName, port);
            Map<String, Object> resp = ok();
            resp.put("protocolName", probe.getProviderName());
            resp.put("version", probe.getProviderVersion());
            resp.put("jarPath", finalFile.toString());
            resp.put("port", port);
            return resp;
        } catch (Exception e) {
            log.error("上传协议失败: {}", e.getMessage(), e);
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
            if (probeCopy != null) {
                try {
                    Files.deleteIfExists(probeCopy);
                } catch (Exception ignored) {
                }
            }
            return fail("上传失败: " + e.getMessage());
        }
    }

    /**
     * 重新启用协议
     */
    @PostMapping("/reload/{name}")
    public Map<String, Object> reload(@PathVariable String name) {
        // 重新启用数据库中未启用或者已删除的协议
        try {
            PortBinding portBinding = portBindingService.selectByName(name);
            ProtocolJarRegistry protocolJarRegistry = protocolJarRegistryService.selectByName(name);
            jarLoader.loadSingleJar(new File(protocolJarRegistry.getJarPath()));
            registry.bindPortToProtocol(portBinding.getPort(), name);
            NettyServerUtil.bindPort(portBinding.getPort());
            HashMap<String, Object> resp = new HashMap<>();
            resp.put("result","ok");
            return resp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }


    /**
     * 重新扫描 protocols 目录并加载所有 jar
     */
    // @PostMapping("/reload")
    // public Map<String, Object> reload() {
    //     List<ProtocolJarLoader.LoadResult> results = jarLoader.scanAndLoad();
    //     Map<String, Object> resp = ok();
    //     resp.put("loaded", results.stream()
    //             .flatMap(r -> r.getProviderInfos().stream())
    //             .collect(Collectors.toList()));
    //     return resp;
    // }

    /**
     * 卸载协议（彻底删除）。
     * <p>
     * 框架会自动处理：
     * - 关闭该协议绑定的所有端口监听
     * - 强制断开这些端口下的所有活跃连接
     * - 调用 Provider.destroy() 清理资源
     * - 释放 ClassLoader 与 jar 文件句柄
     * <p>
     * 因此调用方无需先解绑端口。
     */
    @DeleteMapping("/{name}")
    public Map<String, Object> unload(@PathVariable String name) {
        List<Integer> boundPorts = registry.getBoundPorts(name);
        boolean ok = registry.unregister(name);
        if (ok) {
            protocolJarRegistryService.syncUnload(name);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", ok);
        if (ok && !boundPorts.isEmpty()) {
            resp.put("closedPorts", boundPorts);
            resp.put("message", "已自动关闭端口监听并断开所有活跃连接: " + boundPorts);
        }
        return resp;
    }

    /**
     * 绑定协议到端口：触发 Netty 端口监听。
     * 若端口已在监听，会先关闭再重新绑定，确保 Pipeline 走新协议。
     */
    @PostMapping("/{name}/bind/{port}")
    public Map<String, Object> bindPort(@PathVariable String name, @PathVariable int port) {
        if (registry.getProvider(name) == null) {
            return fail("协议未注册: " + name);
        }
        // 先建立 port→protocol 映射，再绑定端口，避免绑定瞬间连接进来时映射缺失
        if (GlobalCache.ServerPort_ServerSocketChannel_Map.containsKey(port)) {
            NettyServerUtil.closeListen(port);
        }
        registry.bindPortToProtocol(port, name);
        NettyServerUtil.bindPort(port);
        log.info("协议[{}]绑定到端口[{}]", name, port);
        return ok();
    }

    /**
     * 解绑端口：关闭监听 + 踢所有客户端连接 + 移除映射
     */
    @DeleteMapping("/bind/{port}")
    public Map<String, Object> unbindPort(@PathVariable int port) {
        NettyServerUtil.closeListen(port);
        registry.unbindPort(port);
        log.info("端口[{}]已解绑并关闭监听", port);
        return ok();
    }

    /**
     * 列出所有已注册协议 + 端口绑定（含每端口活跃连接数）
     */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("protocols", protocolJarRegistryService.listAllInDb().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("version", p.getVersion());
            m.put("source", p.getSource());
            m.put("description", p.getDescription());
            m.put("loadedAt", p.getLoadedAt());
            m.put("active", p.getActive());
            m.put("deleted", p.getDeleted());
            m.put("jarPath", p.getJarPath());
            return m;
        }).collect(Collectors.toList()));

        // bindings 输出结构：{ port: { protocol, listening, connections } }
        Map<Integer, Map<String, Object>> bindingViews = new LinkedHashMap<>();
        registry.getAllBindings().forEach((port, protoName) -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("protocol", protoName);
            b.put("listening", GlobalCache.ServerPort_ServerSocketChannel_Map.containsKey(port));
            Set<Channel> channels = GlobalCache.ServerPost_SocketChannelSet_Map.get(port);
            b.put("connections", channels == null ? 0 : channels.size());
            bindingViews.put(port, b);
        });
        resp.put("bindings", bindingViews);

        // 顶部概览数字
        Map<String, Object> summary = new LinkedHashMap<>();
        List<com.cas.access.netty.entity.ProtocolJarRegistry> allDbProtocols = protocolJarRegistryService.listAllInDb();
        summary.put("protocolCount", allDbProtocols.size());
        summary.put("externalJarCount", allDbProtocols.stream()
                .map(com.cas.access.netty.entity.ProtocolJarRegistry::getSource)
                .filter(s -> "external".equals(s))
                .count());
        summary.put("boundPortCount", registry.getAllBindings().size());
        int totalConnections = 0;
        for (Map.Entry<Integer, Map<String, Object>> e : bindingViews.entrySet()) {
            totalConnections += (Integer) e.getValue().get("connections");
        }
        summary.put("totalConnections", totalConnections);
        resp.put("summary", summary);
        return resp;
    }

    private Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        return m;
    }

    private Map<String, Object> fail(String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }
}
