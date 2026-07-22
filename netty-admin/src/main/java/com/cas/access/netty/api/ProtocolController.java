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
import java.io.IOException;
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
        ProtocolJarRegistry protocolJarRegistry = protocolJarRegistryService.selectByName(protocolName);
        if (protocolJarRegistry != null && Boolean.TRUE.equals(protocolJarRegistry.getActive())) {
            return fail("协议[" + protocolJarRegistry.getName() + "]已存在");
        }
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

        // 3. 协议名唯一性校验（upload 仅用于新协议，更新请用 update 接口）
        if (registry.getProvider(protocolName) != null) {
            return fail("协议[" + protocolName + "]已存在，如需更新请使用 update 接口");
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

            // 6. rename 到正式文件
            //    注意：即使新协议，目标文件也可能存在（用户之前上传过同名协议 → 卸载 → purge
            //    后 jar 文件可能残留且被旧 ClassLoader 锁住）。
            //    先 GC 重试覆盖；仍失败则降级用带时间戳的新文件名。
            Path finalFile = moveJarFileWithFallback(tempFile, filename);
            tempFile = null; // 已 move，不再清理

            // 7. 正式注册（新协议首次注册，不触发热替换）
            jarLoader.loadSingleJar(finalFile.toFile());

            // 8. 绑定端口（写 DB） + 启动监听
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
     * 更新协议 jar 包（热替换）。
     *
     * <p>用于已有协议的 jar 包更新：上传新版本 jar 覆盖旧 jar，
     * 框架自动完成热替换（关旧端口监听 + 旧连接 → 注册新 Provider → 重新 bind 端口）。
     *
     * <p>与 upload 的区别：
     * <ul>
     *   <li>upload 用于上传新协议（protocolName 不存在）</li>
     *   <li>update 用于更新已有协议的 jar 包（protocolName 已存在）</li>
     * </ul>
     *
     * <p>流程：
     * <ol>
     *   <li>校验协议名存在 + 文件合法</li>
     *   <li>获取当前绑定的端口列表（可能多个）</li>
     *   <li>写临时文件 + probe 校验（jar 内 Provider.name() 必须与 name 一致）</li>
     *   <li>关闭旧 ClassLoader 释放 jar 文件锁</li>
     *   <li>覆盖正式 jar 文件（用原 jarPath，不产生残留文件）</li>
     *   <li>正式注册（register 热替换：关旧端口监听 + 旧连接 + destroy + close CL）</li>
     *   <li>重新 bind 所有端口</li>
     * </ol>
     */
    @PostMapping("/{name}/update")
    public Map<String, Object> update(@PathVariable String name,
                                      @RequestParam("file") MultipartFile file) {

        // 1. 校验协议名存在
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) {
            return fail("协议[" + name + "]不存在，如需上传新协议请使用 upload 接口");
        }

        // 2. 校验文件
        if (file == null || file.isEmpty()) {
            return fail("文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".jar")) {
            return fail("仅支持 .jar 文件");
        }

        // 3. 获取当前绑定的端口列表（热替换前快照，register 后用于重新 bind）
        List<Integer> boundPorts = registry.getBoundPorts(name);

        Path tempFile = null;
        Path probeCopy = null;
        try {
            // 4. 写临时文件
            Path uploadDir = Paths.get(properties.getJarDir(), ".upload").toAbsolutePath();
            Files.createDirectories(uploadDir);
            tempFile = uploadDir.resolve(filename + ".tmp");
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 5. probe 校验（加载副本，避免锁住 tempFile 影响后续 move）
            probeCopy = Files.createTempFile("protocol-probe-", ".jar");
            Files.copy(tempFile, probeCopy, StandardCopyOption.REPLACE_EXISTING);
            ProtocolJarLoader.ProbeResult probe = jarLoader.probe(probeCopy.toFile());
            if (!probe.isSuccess()) {
                Files.deleteIfExists(tempFile);
                return fail("jar 加载失败: " + probe.getErrorMessage());
            }
            // 协议名一致性校验：jar 内 Provider.name() 必须与路径参数 name 一致
            if (!name.equals(probe.getProviderName())) {
                Files.deleteIfExists(tempFile);
                return fail("协议名不匹配：jar 内实际为 [" + probe.getProviderName()
                        + "]，与要更新的协议 [" + name + "] 不符");
            }
            try { Files.deleteIfExists(probeCopy); } catch (Exception ignored) {}
            probeCopy = null;

            // 6. 关闭旧 ClassLoader（释放 jar 文件锁，Windows 上必须先关闭才能覆盖）
            registry.closeClassLoaderForUpgrade(name);

            // 7. 覆盖正式 jar 文件（用原 jarPath，不产生残留文件）
            //    旧 ClassLoader 已在第 6 步关闭，但 Windows 上文件句柄释放依赖 GC。
            //    先 GC 重试覆盖；仍失败则降级用带时间戳的新文件名（syncRegister 会自动更新 DB jarPath）。
            String oldJarPath = existing.getJarPath();
            if (oldJarPath == null || oldJarPath.isEmpty()) {
                return fail("协议[" + name + "] 无 jar 路径记录，无法更新");
            }
            String oldFilename = Paths.get(oldJarPath).getFileName().toString();
            Path finalFile = moveJarFileWithFallback(tempFile, oldFilename);
            tempFile = null;

            // 8. 正式注册（register → unregisterInternal(hotReplace=true) → closeOldChannels）
            //    closeOldChannels 关闭旧端口监听 + 旧连接
            //    不调 syncUnload（port_binding 表保持不变）
            //    syncRegister 更新 protocol_jar_registry（version、jarPath、loadedAt 等）
            jarLoader.loadSingleJar(finalFile.toFile());

            // 9. 重新 bind 所有端口（closeOldChannels 已关闭旧监听，这里恢复）
            for (int port : boundPorts) {
                NettyServerUtil.bindPort(port);
            }

            log.info("协议[{}]更新成功，重新绑定端口 {}", name, boundPorts);
            Map<String, Object> resp = ok();
            resp.put("protocolName", probe.getProviderName());
            resp.put("version", probe.getProviderVersion());
            resp.put("jarPath", finalFile.toString());
            resp.put("reboundPorts", boundPorts);
            return resp;
        } catch (Exception e) {
            log.error("更新协议失败: name={}, err={}", name, e.getMessage(), e);
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
            if (probeCopy != null) {
                try { Files.deleteIfExists(probeCopy); } catch (Exception ignored) {}
            }
            return fail("更新失败: " + e.getMessage());
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
            String message = "重新启用协议失败: 请检查协议jar是否存在以及端口是否被占用" + e.getMessage();
            throw new RuntimeException(message);
        }

    }

    /**
     * 卸载协议（停止运行时 + 标记 active=false，DB 记录保留）。
     * <p>
     * 框架会自动处理：
     * - 关闭该协议绑定的所有端口监听
     * - 强制断开这些端口下的所有活跃连接
     * - 调用 Provider.destroy() 清理资源
     * - 释放 ClassLoader 与 jar 文件句柄
     * <p>
     * 因此调用方无需先解绑端口。
     * <p>
     * 卸载后协议记录仍保留在 DB（active=false），可通过 reload 接口重新启用，
     * 或通过 purge 接口彻底物理删除。
     */
    @DeleteMapping("/{name}")
    public Map<String, Object> unload(@PathVariable String name) {
        List<Integer> boundPorts = registry.getBoundPorts(name);
        boolean ok = registry.unregister(name);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", ok);
        if (ok && !boundPorts.isEmpty()) {
            resp.put("closedPorts", boundPorts);
            resp.put("message", "已自动关闭端口监听并断开所有活跃连接: " + boundPorts);
        }
        return resp;
    }

    /**
     * 彻底删除协议（物理删除 DB 记录 + jar 文件，不可恢复）。
     *
     * <p>前置条件：协议必须已卸载（active=false）。活跃协议请先调用
     * {@link #unload(String)} 卸载后再删除。
     *
     * <p>本接口清理：
     * <ul>
     *   <li>protocol_jar_registry 表记录</li>
     *   <li>port_protocol_binding 表中该协议的所有绑定记录</li>
     *   <li>protocols 目录下的 jar 文件（卸载时 ClassLoader 已关闭，但 Windows 上
     *       文件句柄释放依赖 GC，删除失败时会触发 GC 重试）</li>
     * </ul>
     *
     * @param name 协议名
     */
    @DeleteMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable String name) {
        // 1. 校验协议存在
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) {
            return fail("协议[" + name + "]不存在");
        }

        // 2. 校验不活跃（DB active=false）
        if (Boolean.TRUE.equals(existing.getActive())) {
            return fail("协议[" + name + "]处于活跃状态，请先卸载再删除");
        }

        // 3. 双保险：校验运行时内存中无注册
        if (registry.getProvider(name) != null) {
            return fail("协议[" + name + "]仍在运行时注册表中，请先卸载再删除");
        }

        // 4. 物理删除 DB 记录
        boolean ok = protocolJarRegistryService.purgeByName(name);
        if (!ok) {
            return fail("协议[" + name + "]删除失败（记录不存在）");
        }

        // 5. 删除 jar 文件（彻底删除语义）
        //    卸载时 ClassLoader 已 close，
        //    用 GC 重试。删除失败不阻断 purge 主流程（DB 记录已删），仅 warn。
        String jarPath = existing.getJarPath();
        boolean jarDeleted = false;
        if (jarPath != null && !jarPath.isEmpty()) {
            jarDeleted = deleteJarWithGcRetry(Paths.get(jarPath).toAbsolutePath());
        }

        log.info("协议[{}]已彻底删除（DB 记录已删，jar 文件{}）",
                name, jarDeleted ? "已删" : "删除失败或不存在");
        Map<String, Object> resp = ok();
        resp.put("purgedProtocol", name);
        resp.put("jarDeleted", jarDeleted);
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

    /* ========== Windows jar 文件锁处理 ========== */

    /**
     * 移动文件，Windows 上目标文件被占用时触发 GC + Finalizer 后重试。
     *
     * <p>背景：{@code URLClassLoader.close()} 在 Java 8 + Windows 上不保证立即释放
     * jar 文件句柄（JDK-8054458）。底层 JarFile 释放依赖 GC，且某些情况下需要
     * finalizer 执行后才真正释放。{@code System.gc()} + {@code System.runFinalization()}
     * 促使 JVM 回收 JarFile 并执行 pending finalizer。
     *
     * @param source 源文件
     * @param target 目标文件（已存在则覆盖）
     * @throws IOException 重试次数用尽后仍失败时抛出最后一次异常
     */
    private void moveWithGcRetry(Path source, Path target) throws IOException {
        final int maxRetries = 4;
        final long intervalMs = 1000;
        IOException last = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                if (i == maxRetries) {
                    break;
                }
                log.warn("文件移动失败（可能被旧 ClassLoader 锁定），触发 GC+Finalizer 后重试 ({}/{}): target={}",
                        i + 1, maxRetries, target);
                forceGcAndFinalize();
                sleep(intervalMs);
            }
        }
        throw new IOException("文件移动失败（已重试 " + maxRetries + " 次）: " + target
                + " — 可能被旧 ClassLoader 锁定，请稍后重试或重启服务", last);
    }

    /**
     * 移动 jar 文件到目标位置，如果目标文件被锁定（旧 ClassLoader 未释放），
     * 降级使用带时间戳的新文件名，避免 upload/update 因文件锁失败。
     *
     * <p>降级后旧 jar 文件残留在 protocols 目录，待后续 GC 释放句柄后可手动删除。
     * 新 jarPath 会通过 {@code syncRegister} 自动更新到 DB。
     *
     * @param tempFile 临时文件
     * @param preferredFilename 期望的文件名（可能与旧 jar 同名）
     * @return 最终使用的文件路径
     * @throws IOException 降级后仍失败时抛出
     */
    private Path moveJarFileWithFallback(Path tempFile, String preferredFilename) throws IOException {
        Path target = Paths.get(properties.getJarDir(), preferredFilename).toAbsolutePath();
        try {
            moveWithGcRetry(tempFile, target);
            return target;
        } catch (IOException e) {
            // 降级：用带时间戳的新文件名，不覆盖旧 jar
            String baseName = preferredFilename.toLowerCase().endsWith(".jar")
                    ? preferredFilename.substring(0, preferredFilename.length() - 4)
                    : preferredFilename;
            String newName = baseName + "-" + System.currentTimeMillis() + ".jar";
            Path fallback = Paths.get(properties.getJarDir(), newName).toAbsolutePath();
            Files.move(tempFile, fallback, StandardCopyOption.REPLACE_EXISTING);
            log.warn("旧 jar 文件[{}]被锁定，已降级使用新文件名[{}]", preferredFilename, fallback.getFileName());
            return fallback;
        }
    }

    /**
     * 删除 jar 文件，Windows 上文件被占用时触发 GC + Finalizer 后重试。
     *
     * <p>同 {@link #moveWithGcRetry} 的背景：卸载协议后 jar 文件句柄可能未释放。
     *
     * @param jarPath jar 文件路径
     * @return true=删除成功或文件不存在；false=重试后仍删除失败
     */
    private boolean deleteJarWithGcRetry(Path jarPath) {
        if (!Files.exists(jarPath)) {
            log.info("jar 文件不存在，跳过删除: {}", jarPath);
            return true;
        }
        final int maxRetries = 4;
        final long intervalMs = 1000;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                Files.deleteIfExists(jarPath);
                log.info("已删除 jar 文件: {}", jarPath);
                return true;
            } catch (IOException e) {
                if (i == maxRetries) {
                    log.warn("删除 jar 文件失败（已重试 {} 次）: {} — 协议 DB 记录已删，"
                            + "jar 文件可稍后手动删除或等待 JVM 释放句柄后删除", maxRetries, jarPath);
                    return false;
                }
                log.warn("删除 jar 文件失败（可能被旧 ClassLoader 锁定），触发 GC+Finalizer 后重试 ({}/{}): {}",
                        i + 1, maxRetries, jarPath);
                forceGcAndFinalize();
                sleep(intervalMs);
            }
        }
        return false;
    }

    /**
     * 强制触发 GC 并执行 pending finalizer。
     *
     * <p>Java 8 + Windows 上 URLClassLoader.close() 后 JarFile 文件句柄可能未释放，
     * 依赖 GC 回收 JarFile 对象 + finalizer 关闭句柄（JDK-8054458）。
     * {@code System.gc()} 触发 GC，{@code System.runFinalization()} 强制执行
     * pending finalizer，两者配合提高文件句柄释放概率。
     */
    private void forceGcAndFinalize() {
        System.gc();
        System.runFinalization();
        System.gc();  // 再次 GC 清理 finalizer 释放的对象
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
