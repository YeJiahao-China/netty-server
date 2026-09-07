package com.cas.access.netty.service;

import com.cas.access.netty.entity.ProtocolJarRegistry;
import com.cas.access.netty.protocol.LoadedProtocol;
import com.cas.access.netty.protocol.ProtocolJarLoader;
import com.cas.access.netty.protocol.ProtocolProperties;
import com.cas.access.netty.protocol.ProtocolRegistry;
import com.cas.access.netty.util.NettyServerUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 协议热插拔补偿服务。
 *
 * <p>为 V2 集群分发接口提供原子化的协议操作，并支持失败后的补偿回滚：
 * <ul>
 *   <li>{@link #upload}：上传 jar + 绑定端口 + 开启监听。</li>
 *   <li>{@link #update}：更新 jar，覆盖前自动备份旧 jar。</li>
 *   <li>{@link #rollbackUpdate}：从备份恢复旧版本 jar。</li>
 *   <li>{@link #cleanupUpload}：unload + 删 jar + 删 DB 记录。</li>
 * </ul>
 *
 * <p>本类作为新增 Service 存在，不修改现有 {@link ProtocolController} 代码。
 */
@Slf4j
@Service
public class ProtocolCompensationService {

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
     * 上传协议 jar 并绑定端口。
     *
     * @param protocolName 协议名
     * @param port         绑定端口
     * @param jarBytes     jar 二进制内容
     * @param fileName     原始文件名
     * @return 结果 map，含 protocolName / version / jarPath / port
     */
    public Map<String, Object> upload(String protocolName, int port, byte[] jarBytes, String fileName) throws Exception {
        if (jarBytes == null || jarBytes.length == 0) return fail("jar 内容为空");
        if (fileName == null || !fileName.toLowerCase().endsWith(".jar")) return fail("仅支持 .jar 文件");
        if (protocolName == null || protocolName.isBlank()) return fail("protocolName 不能为空");
        if (port < 1024 || port > 65535) return fail("端口范围必须在 1024-65535");

        ProtocolJarRegistry exist = protocolJarRegistryService.selectByName(protocolName);
        if (exist != null && Boolean.TRUE.equals(exist.getActive())) return fail("协议[" + protocolName + "]已存在");
        String existingProtocol = registry.getProtocolNameByPort(port);
        if (existingProtocol != null) return fail("端口 " + port + " 已被协议[" + existingProtocol + "]占用");
        if (registry.getProvider(protocolName) != null) return fail("协议[" + protocolName + "]已存在，请走 update");

        Path uploadDir = Paths.get(properties.getJarDir(), ".upload").toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path temp = uploadDir.resolve(fileName + ".tmp");
        Path probeCopy = null;
        try {
            Files.write(temp, jarBytes);
            probeCopy = Files.createTempFile("protocol-probe-", ".jar");
            Files.copy(temp, probeCopy, StandardCopyOption.REPLACE_EXISTING);

            ProtocolJarLoader.ProbeResult probe = jarLoader.probe(probeCopy.toFile());
            if (!probe.isSuccess()) return fail("jar 加载失败: " + probe.getErrorMessage());
            if (!protocolName.equals(probe.getProviderName())) {
                return fail("协议名不匹配: jar内[" + probe.getProviderName() + "] vs 输入[" + protocolName + "]");
            }

            Path finalFile = moveJarFileWithFallback(temp, fileName);
            jarLoader.loadSingleJar(finalFile.toFile());
            registry.bindPortToProtocol(port, protocolName);
            try {
                NettyServerUtil.bindPort(port);
            } catch (Exception bindEx) {
                log.error("启动端口[{}]监听失败，回滚协议[{}]注册: {}", port, protocolName, bindEx.getMessage());
                registry.unregister(protocolName);
                try { Files.deleteIfExists(finalFile); } catch (Exception ignored) {}
                return fail("端口 " + port + " 启动监听失败: " + bindEx.getMessage());
            }

            Map<String, Object> r = ok();
            r.put("protocolName", probe.getProviderName());
            r.put("version", probe.getProviderVersion());
            r.put("jarPath", finalFile.toString());
            r.put("port", port);
            return r;
        } finally {
            if (probeCopy != null) {
                try { Files.deleteIfExists(probeCopy); } catch (Exception ignored) {}
            }
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 更新协议 jar，覆盖前自动备份旧 jar。
     *
     * @param name     协议名
     * @param jarBytes jar 二进制内容
     * @param fileName 原始文件名
     * @return 结果 map，含 protocolName / version / jarPath / reboundPorts / failedPorts
     */
    public Map<String, Object> update(String name, byte[] jarBytes, String fileName) throws Exception {
        if (jarBytes == null || jarBytes.length == 0) return fail("jar 内容为空");
        if (fileName == null || !fileName.toLowerCase().endsWith(".jar")) return fail("仅支持 .jar 文件");
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) return fail("协议[" + name + "]不存在");

        List<Integer> boundPorts = registry.getBoundPorts(name);
        Path uploadDir = Paths.get(properties.getJarDir(), ".upload").toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path temp = uploadDir.resolve(fileName + ".tmp");
        Path probeCopy = null;
        try {
            Files.write(temp, jarBytes);
            probeCopy = Files.createTempFile("protocol-probe-", ".jar");
            Files.copy(temp, probeCopy, StandardCopyOption.REPLACE_EXISTING);

            ProtocolJarLoader.ProbeResult probe = jarLoader.probe(probeCopy.toFile());
            if (!probe.isSuccess()) return fail("jar 加载失败: " + probe.getErrorMessage());
            if (!name.equals(probe.getProviderName())) {
                return fail("协议名不匹配: jar内[" + probe.getProviderName() + "] vs 目标[" + name + "]");
            }

            // 备份旧 jar
            String oldJarPath = existing.getJarPath();
            if (oldJarPath != null && !oldJarPath.isEmpty()) {
                backupJar(oldJarPath, name);
            }

            registry.closeOldChannels(name);
            registry.closeClassLoaderForUpgrade(name);

            Path finalFile = moveJarFileWithFallback(temp, fileName);
            if (oldJarPath != null && !oldJarPath.isEmpty()) {
                Path old = Paths.get(oldJarPath).toAbsolutePath();
                if (!old.equals(finalFile.toAbsolutePath())) {
                    try { Files.deleteIfExists(old); } catch (IOException ignored) {}
                }
            }

            jarLoader.loadSingleJar(finalFile.toFile());
            List<Integer> rebound = new ArrayList<>();
            List<Integer> failed = new ArrayList<>();
            for (int port : boundPorts) {
                try {
                    NettyServerUtil.bindPort(port);
                    rebound.add(port);
                } catch (Exception bindEx) {
                    failed.add(port);
                    log.warn("协议[{}]更新后重新绑定端口[{}]失败: {}", name, port, bindEx.getMessage());
                }
            }

            Map<String, Object> r = ok();
            r.put("protocolName", probe.getProviderName());
            r.put("version", probe.getProviderVersion());
            r.put("jarPath", finalFile.toString());
            r.put("reboundPorts", rebound);
            if (!failed.isEmpty()) r.put("failedPorts", failed);
            return r;
        } finally {
            if (probeCopy != null) {
                try { Files.deleteIfExists(probeCopy); } catch (Exception ignored) {}
            }
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 回滚 update：用备份的旧 jar 覆盖当前 jar 并重新加载。
     *
     * @param name 协议名
     * @return 结果 map
     */
    public Map<String, Object> rollbackUpdate(String name) throws Exception {
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) return fail("协议[" + name + "]不存在");

        Path backup = findLatestBackup(name);
        if (backup == null || !Files.exists(backup)) {
            return fail("协议[" + name + "]无可用备份，无法回滚");
        }

        String currentJarPath = existing.getJarPath();
        if (currentJarPath == null || currentJarPath.isEmpty()) {
            return fail("协议[" + name + "]当前 jar 路径为空，无法回滚");
        }
        Path target = Paths.get(currentJarPath).toAbsolutePath();

        List<Integer> boundPorts = registry.getBoundPorts(name);
        registry.closeOldChannels(name);
        registry.closeClassLoaderForUpgrade(name);

        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("协议[{}]已从备份[{}]恢复到[{}]", name, backup, target);

        jarLoader.loadSingleJar(target.toFile());
        List<Integer> rebound = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (int port : boundPorts) {
            try {
                NettyServerUtil.bindPort(port);
                rebound.add(port);
            } catch (Exception bindEx) {
                failed.add(port);
                log.warn("协议[{}]回滚后重新绑定端口[{}]失败: {}", name, port, bindEx.getMessage());
            }
        }

        Map<String, Object> r = ok();
        r.put("protocolName", name);
        r.put("jarPath", target.toString());
        r.put("reboundPorts", rebound);
        if (!failed.isEmpty()) r.put("failedPorts", failed);
        return r;
    }

    /**
     * 清理 upload 产生的状态：unload + 删 jar + 删 DB 记录。
     *
     * @param name 协议名
     */
    public Map<String, Object> cleanupUpload(String name) {
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) {
            return ok();
        }

        // 1. 卸载：停止监听、解绑端口、关闭 ClassLoader、DB active=false
        if (registry.getProvider(name) != null) {
            registry.unregister(name);
        }

        // 2. 删除 jar 文件
        String jarPath = existing.getJarPath();
        if (jarPath != null && !jarPath.isEmpty()) {
            deleteJarFile(Paths.get(jarPath).toAbsolutePath());
        }

        // 3. 物理删除 DB 记录
        protocolJarRegistryService.purgeByName(name);
        log.info("协议[{}] cleanup-upload 完成", name);
        return ok();
    }

    /* ========== 简单委托操作 ========== */

    public Map<String, Object> bind(String name, int port) {
        if (registry.getProvider(name) == null) return fail("协议未注册: " + name);
        registry.bindPortToProtocol(port, name);
        NettyServerUtil.bindPort(port);
        log.info("协议[{}]绑定到端口[{}]", name, port);
        return ok();
    }

    public Map<String, Object> unbind(int port) {
        NettyServerUtil.closeListen(port);
        registry.unbindPort(port);
        log.info("端口[{}]已解绑并关闭监听", port);
        return ok();
    }

    public Map<String, Object> unload(String name) {
        List<Integer> boundPorts = registry.getBoundPorts(name);
        boolean ok = registry.unregister(name);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        if (ok && !boundPorts.isEmpty()) {
            resp.put("closedPorts", boundPorts);
        }
        return resp;
    }

    public Map<String, Object> purge(String name) {
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) return fail("协议[" + name + "]不存在");
        if (Boolean.TRUE.equals(existing.getActive())) return fail("协议[" + name + "]处于活跃状态，请先卸载");
        if (registry.getProvider(name) != null) return fail("协议[" + name + "]仍在运行时注册表中");

        protocolJarRegistryService.purgeByName(name);
        String jarPath = existing.getJarPath();
        boolean jarDeleted = false;
        if (jarPath != null && !jarPath.isEmpty()) {
            jarDeleted = deleteJarFile(Paths.get(jarPath).toAbsolutePath());
        }
        Map<String, Object> r = ok();
        r.put("purgedProtocol", name);
        r.put("jarDeleted", jarDeleted);
        return r;
    }

    public Map<String, Object> reload(String name) {
        ProtocolJarRegistry existing = protocolJarRegistryService.selectByName(name);
        if (existing == null) return fail("协议[" + name + "]不存在");
        String jarPath = existing.getJarPath();
        if (jarPath == null || jarPath.isEmpty()) return fail("协议[" + name + "]无 jar 路径");
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) return fail("协议[" + name + "]的 jar 文件不存在: " + jarPath);

        List<Integer> ports = portBindingService.selectAllPortsByProtocol(name);
        jarLoader.loadSingleJar(jarFile);
        List<Integer> boundPorts = new ArrayList<>();
        for (int port : ports) {
            registry.bindPortToProtocol(port, name);
            NettyServerUtil.bindPort(port);
            boundPorts.add(port);
        }
        Map<String, Object> r = ok();
        r.put("protocolName", name);
        r.put("reboundPorts", boundPorts);
        return r;
    }

    public Map<String, Object> reloadAll() {
        jarLoader.scanAndLoad();
        return ok();
    }

    /* ========== jar 文件与备份工具 ========== */

    private Path moveJarFileWithFallback(Path tempFile, String preferredFilename) throws IOException {
        Path target = Paths.get(properties.getJarDir(), preferredFilename).toAbsolutePath();
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            String baseName = preferredFilename.toLowerCase().endsWith(".jar")
                    ? preferredFilename.substring(0, preferredFilename.length() - 4)
                    : preferredFilename;
            String newName = baseName + "-" + System.currentTimeMillis() + ".jar";
            Path fallback = Paths.get(properties.getJarDir(), newName).toAbsolutePath();
            Files.move(tempFile, fallback, StandardCopyOption.REPLACE_EXISTING);
            log.warn("目标 jar 文件[{}]被占用，已降级使用新文件名[{}]", preferredFilename, fallback.getFileName());
            return fallback;
        }
    }

    private boolean deleteJarFile(Path jarPath) {
        if (!Files.exists(jarPath)) return true;
        try {
            Files.deleteIfExists(jarPath);
            log.info("已删除 jar 文件: {}", jarPath);
            return true;
        } catch (IOException e) {
            log.warn("删除 jar 文件失败: {} — DB 记录可能已删", jarPath);
            return false;
        }
    }

    private void backupJar(String jarPath, String protocolName) throws IOException {
        Path source = Paths.get(jarPath).toAbsolutePath();
        if (!Files.exists(source)) {
            log.warn("旧 jar 文件不存在，跳过备份: {}", jarPath);
            return;
        }
        Path backupDir = Paths.get(properties.getJarDir(), ".backup").toAbsolutePath();
        Files.createDirectories(backupDir);
        String backupName = protocolName + "-" + System.currentTimeMillis() + ".jar";
        Path backup = backupDir.resolve(backupName);
        Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        log.info("协议[{}]旧 jar 已备份到 {}", protocolName, backup);
    }

    private Path findLatestBackup(String protocolName) {
        Path backupDir = Paths.get(properties.getJarDir(), ".backup").toAbsolutePath();
        if (!Files.isDirectory(backupDir)) return null;
        Path latest = null;
        long latestTime = 0;
        try (Stream<Path> stream = Files.list(backupDir)) {
            for (Path p : stream.toList()) {
                String fn = p.getFileName().toString();
                if (fn.startsWith(protocolName + "-") && fn.toLowerCase().endsWith(".jar")) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > latestTime) {
                        latestTime = t;
                        latest = p;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描备份目录失败: {}", e.getMessage());
        }
        return latest;
    }

    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        return m;
    }

    private Map<String, Object> fail(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }
}
