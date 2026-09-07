package com.cas.admin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cas.admin.cluster.CompensatingNodeBroadcastClient;
import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.admin.common.ApiResponse;
import com.cas.admin.common.NodeType;
import com.cas.admin.entity.PortProtocolBinding;
import com.cas.cluster.node.entity.ProtocolJarRegistry;
import com.cas.admin.mapper.PortProtocolBindingMapper;
import com.cas.admin.mapper.ProtocolJarRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 协议热插拔 V2 API（补偿回滚版）。
 *
 * <p>与现有 {@link ProtocolAdminController} 行为一致，但新增“部分节点失败时自动补偿回滚”能力：
 * <ul>
 *   <li>{@code upload} 失败：对已成功的节点执行 unload + 删 jar + 删 DB 记录。</li>
 *   <li>{@code update} 失败：对已成功的节点从备份恢复旧版本 jar。</li>
 *   <li>{@code bind} 失败：对已成功的节点执行 unbind。</li>
 * </ul>
 *
 * <p>本类作为新增 Controller 存在，不修改现有 {@link ProtocolAdminController} 代码。</p>
 */
@Slf4j
@RestController
@RequestMapping("/protocols/v2")
@RequiredArgsConstructor
public class ProtocolAdminV2Controller {

    private static final String INTERNAL_DISTRIBUTE_PATH = "/protocols/v2/internal/distribute";

    private final CompensatingNodeBroadcastClient broadcast;
    private final ProtocolJarRegistryMapper registryMapper;
    private final PortProtocolBindingMapper portBindingMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ========== 查询类：直查同库 ========== */

    @GetMapping
    public Map<String, Object> list() {
        List<ProtocolJarRegistry> allProtocols = registryMapper.selectAll();
        List<PortProtocolBinding> allBindings = portBindingMapper.selectAll();

        // 端口绑定按 protocolName 分组
        Map<String, Integer> protoPortMap = new LinkedHashMap<>();
        for (PortProtocolBinding b : allBindings) {
            if (b.getProtocolName() != null && b.getPort() != null) {
                protoPortMap.put(b.getProtocolName(), b.getPort());
            }
        }

        // 协议列表
        List<Map<String, Object>> protocols = allProtocols.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("version", p.getVersion());
            m.put("port", protoPortMap.getOrDefault(p.getName(), null));
            m.put("source", p.getSource());
            m.put("description", p.getDescription());
            m.put("loadedAtText", p.getLoadedAt() != null ? p.getLoadedAt().format(DT_FMT) : null);
            m.put("jarPath", p.getJarPath());
            m.put("status", p.getStatus());
            return m;
        }).collect(Collectors.toList());

        // 概览
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("protocolCount", allProtocols.size());
        summary.put("externalJarCount", allProtocols.stream()
                .map(ProtocolJarRegistry::getSource)
                .filter("external"::equals)
                .count());
        summary.put("boundPortCount", allBindings.size());
        summary.put("totalConnections", 0); // cluster-admin 无法获取实时连接数，由前端从 iot-access 取

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("protocols", protocols);
        resp.put("summary", summary);
        return resp;
    }

    /* ========== 上传 / 更新 / 绑定（需要补偿回滚） ========== */

    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("port") int port,
            @RequestParam("protocolName") String protocolName) {
        if (file == null || file.isEmpty()) return fail("请上传协议 jar 文件");
        if (protocolName == null || protocolName.isBlank()) return fail("protocolName 不能为空");
        try {
            byte[] bytes = file.getBytes();
            // 1. 存 jar 到 DB 仓库表，status=INIT
            saveJarToRepo(protocolName, bytes);
            // 2. 广播 sync-upload（节点从 DB 拉）
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "sync-upload");
            body.put("protocolName", protocolName);
            body.put("port", port);
            body.put("fileName", file.getOriginalFilename());
            return doSyncDistribute(body, protocolName);
        } catch (Exception e) {
            log.error("上传协议失败", e);
            return fail("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/{name}/update")
    public Map<String, Object> update(
            @PathVariable("name") String name,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return fail("请上传协议 jar 文件");
        try {
            byte[] bytes = file.getBytes();
            // 1. 更新 DB 仓库表 jar 字节，status=INIT
            saveJarToRepo(name, bytes);
            // 2. 广播 sync-update（节点从 DB 拉新 jar 热替换）
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "sync-update");
            body.put("protocolName", name);
            body.put("fileName", file.getOriginalFilename());
            return doSyncDistribute(body, name);
        } catch (Exception e) {
            log.error("V2 update 失败", e);
            return fail("更新失败: " + e.getMessage());
        }
    }

    @PostMapping("/{name}/bind/{port}")
    public Map<String, Object> bind(
            @PathVariable("name") String name,
            @PathVariable("port") int port) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "bind");
        body.put("protocolName", name);
        body.put("port", port);
        return doDistribute(body, true);
    }

    /* ========== 纯指令类（不自动回滚，仅返回失败明细） ========== */

    @PostMapping({"/reload", "/reload/all"})
    public Map<String, Object> reloadAll() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "reload");
        body.put("protocolName", "all");
        return doDistribute(body, false);
    }

    @PostMapping("/reload/{name}")
    public Map<String, Object> reload(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "reload");
        body.put("protocolName", name);
        return doDistribute(body, false);
    }

    @DeleteMapping("/bind/{port}")
    public Map<String, Object> unbind(@PathVariable("port") int port) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "unbind");
        body.put("port", port);
        return doDistribute(body, false);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> unload(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "unload");
        body.put("protocolName", name);
        return doDistribute(body, false);
    }

    @DeleteMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable("name") String name) {
        ProtocolJarRegistry existing = registryMapper.selectByName(name);
        if (existing == null) {
            return fail("协议[" + name + "]不存在");
        }
        // INIT 状态：jar 从未分发到节点，直接物理删除 DB 记录，无需广播
        if ("INIT".equals(existing.getStatus())) {
            int deleted = registryMapper.delete(new LambdaQueryWrapper<ProtocolJarRegistry>()
                    .eq(ProtocolJarRegistry::getName, name));
            log.info("INIT 状态协议直接删除 DB 记录: name={}, deleted={}", name, deleted);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("total", 0);
            r.put("successCount", 0);
            r.put("failureCount", 0);
            r.put("reason", "INIT 状态协议从未分发，仅删除 DB 记录");
            return r;
        }
        // 其他状态（UNLOADED/FAILED）：广播通知节点清理（尽力而为），无论广播结果如何都删除 DB 记录
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "purge");
        body.put("protocolName", name);
        Map<String, Object> distResult = doDistribute(body, false);

        // 无论节点广播是否成功，purge 语义是彻底删除 DB 记录
        int deleted = registryMapper.delete(new LambdaQueryWrapper<ProtocolJarRegistry>()
                .eq(ProtocolJarRegistry::getName, name));
        log.info("purge 删除 DB 记录: name={}, deleted={}, broadcastSuccess={}",
                name, deleted, distResult.get("success"));

        // 返回广播结果作为参考，但 purge 本身视为成功
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", distResult.get("total"));
        r.put("successCount", distResult.get("successCount"));
        r.put("failureCount", distResult.get("failureCount"));
        r.put("results", distResult.get("results"));
        if (!Boolean.TRUE.equals(distResult.get("success"))) {
            r.put("warning", "节点清理未全部成功，但 DB 记录已删除");
        }
        return r;
    }

    /* ========== 公共分发 + 补偿回滚 ========== */

    private Map<String, Object> doDistribute(Map<String, Object> body, boolean compensable) {
        List<NodeResult> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isSuccess).count();
        // 无可用节点（results 为空）视为失败，避免误判为"全部成功"
        boolean hasFailure = results.isEmpty() || ok < results.size();
        List<NodeResult> successNodes = results.stream()
                .filter(NodeResult::isSuccess)
                .collect(Collectors.toList());

        Map<String, Object> compensation = null;
        if (compensable && hasFailure && !successNodes.isEmpty()) {
            Map<String, Object> rollbackBody = buildRollbackBody(body);
            if (rollbackBody != null) {
                log.warn("协议操作部分节点失败，触发补偿回滚: mode={}, successCount={}, failureCount={}",
                        body.get("mode"), ok, results.size() - ok);
                List<NodeResult> compResults = broadcast.broadcastExplicit(
                        successNodes.stream().map(NodeResult::getNode).collect(Collectors.toList()),
                        "POST", INTERNAL_DISTRIBUTE_PATH, rollbackBody);
                long compOk = compResults.stream().filter(NodeResult::isSuccess).count();
                compensation = new LinkedHashMap<>();
                compensation.put("rollbackMode", rollbackBody.get("mode"));
                compensation.put("total", compResults.size());
                compensation.put("successCount", compOk);
                compensation.put("failureCount", compResults.size() - compOk);
                compensation.put("results", compResults.stream().map(NodeResult::getResult).toList());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", !hasFailure);
        if (hasFailure) {
            r.put("reason", results.isEmpty()
                    ? "无可用 ACCESS 节点，操作无法执行"
                    : "部分节点执行失败");
        }
        r.put("total", results.size());
        r.put("successCount", ok);
        r.put("failureCount", results.size() - ok);
        r.put("results", results.stream().map(NodeResult::getResult).toList());
        if (compensation != null) {
            r.put("compensation", compensation);
        }
        return r;
    }

    private Map<String, Object> buildRollbackBody(Map<String, Object> original) {
        String mode = (String) original.get("mode");
        Map<String, Object> rb = new LinkedHashMap<>();
        switch (mode) {
            case "upload":
                rb.put("mode", "cleanup-upload");
                rb.put("protocolName", original.get("protocolName"));
                return rb;
            case "update":
                rb.put("mode", "rollback-update");
                rb.put("protocolName", original.get("protocolName"));
                return rb;
            case "bind":
                rb.put("mode", "unbind");
                rb.put("port", original.get("port"));
                return rb;
            default:
                return null;
        }
    }

    private Map<String, Object> fail(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }

    /* ========== protocol_jar 仓库表操作 ========== */

    /** 上传/更新时把 jar 字节写入 protocol_jar_registry 表（status 重置为 INIT） */
    private void saveJarToRepo(String protocolName, byte[] jarBytes) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (registryMapper.existsByName(protocolName)) {
                registryMapper.updateJarBytes(protocolName, jarBytes, now);
            } else {
                ProtocolJarRegistry reg = new ProtocolJarRegistry();
                reg.setName(protocolName);
                reg.setSource("external");
                reg.setJarBytes(jarBytes);
                reg.setStatus("INIT");
                reg.setUpdatedAt(now);
                registryMapper.insert(reg);
            }
            log.info("仓库保存协议jar成功: name={}, size={}KB, status=INIT", protocolName, jarBytes.length / 1024);
        } catch (Exception e) {
            log.error("仓库保存协议jar失败: name={}", protocolName, e);
            throw new RuntimeException(e);
        }
    }

    /* ========== sync 模式：广播 → 等待 → 状态管理 + 保守回滚 ========== */

    /**
     * sync 模式分发：广播 sync-upload/sync-update → 所有节点从 DB 拉 jar → 等待结果
     * 全部成功 → status=REGISTERED
     * 有失败   → 对已成功节点回滚 → status=FAILED
     */
    private Map<String, Object> doSyncDistribute(Map<String, Object> body, String protocolName) {
        String mode = (String) body.get("mode");
        List<NodeResult> results = broadcast.broadcast(NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isSuccess).count();
        // 无可用节点（results 为空）视为失败，避免误判为"全部成功"
        boolean hasFailure = results.isEmpty() || ok < results.size();
        LocalDateTime now = LocalDateTime.now();

        if (!hasFailure) {
            // 全部成功
            registryMapper.updateStatus(protocolName, "REGISTERED", now);
            log.info("协议[{}]注册成功: {}/{} 节点全部成功", protocolName, ok, results.size());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("total", results.size());
            r.put("successCount", ok);
            r.put("failureCount", 0);
            r.put("results", results.stream().map(NodeResult::getResult).toList());
            r.put("status", "REGISTERED");
            return r;
        }

        // 有失败：保守回滚已成功节点
        List<NodeResult> successNodes = results.stream().filter(NodeResult::isSuccess).toList();

        Map<String, Object> compensation = null;
        if (!successNodes.isEmpty()) {
            // sync-upload 失败 → cleanup-upload（unload + 删 jar + 删 DB）
            // sync-update 失败 → rollback-update（从备份恢复旧 jar）
            String rollbackMode = "sync-upload".equals(mode) ? "cleanup-upload" : "rollback-update";
            Map<String, Object> rollbackBody = new LinkedHashMap<>();
            rollbackBody.put("mode", rollbackMode);
            rollbackBody.put("protocolName", protocolName);
            rollbackBody.put("originalMode", mode);
            log.warn("协议[{}]部分节点失败，触发保守回滚({}): 成功 {} 个需回滚", protocolName, rollbackMode, successNodes.size());
            List<NodeResult> compResults = broadcast.broadcastExplicit(
                    successNodes.stream().map(NodeResult::getNode).collect(Collectors.toList()),
                    "POST", INTERNAL_DISTRIBUTE_PATH, rollbackBody);
            long compOk = compResults.stream().filter(NodeResult::isSuccess).count();
            compensation = new LinkedHashMap<>();
            compensation.put("rollbackMode", rollbackMode);
            compensation.put("total", compResults.size());
            compensation.put("successCount", compOk);
            compensation.put("failureCount", compResults.size() - compOk);
            compensation.put("results", compResults.stream().map(NodeResult::getResult).toList());
        }

        registryMapper.updateStatus(protocolName, "FAILED", now);
        log.warn("协议[{}]注册失败: 成功 {}/{}", protocolName, ok, results.size());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("reason", results.isEmpty() ? "无可用 ACCESS 节点，协议无法分发" : "部分节点执行失败，已回滚已成功节点");
        r.put("total", results.size());
        r.put("successCount", ok);
        r.put("failureCount", results.size() - ok);
        r.put("results", results.stream().map(NodeResult::getResult).toList());
        r.put("status", "FAILED");
        if (compensation != null) {
            r.put("compensation", compensation);
        }
        return r;
    }

}
