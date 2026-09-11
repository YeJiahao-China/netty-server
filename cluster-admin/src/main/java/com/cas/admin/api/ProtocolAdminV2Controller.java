package com.cas.admin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.admin.cluster.CompensatingNodeBroadcastClient;
import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.admin.cluster.ProtocolOperationGuard;
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
 * <p>与现有 {@link } 行为一致，但新增“部分节点失败时自动补偿回滚”能力：
 * <ul>
 *   <li>{@code upload} 失败：对已成功的节点执行 unload + 删 jar + 删 DB 记录。</li>
 *   <li>{@code update} 失败：对已成功的节点从备份恢复旧版本 jar。</li>
 *   <li>{@code bind} 失败：对已成功的节点执行 unbind。</li>
 * </ul>
 *
 * <p>本类作为新增 Controller 存在，不修改现有 {@link } 代码。</p>
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
    private final ProtocolOperationGuard operationGuard;
    private final com.cas.admin.cluster.ProtocolUnloadRetryService unloadRetryService;
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
        if (protocolName == null || protocolName.isBlank()) return fail("协议名称不能为空");
        if (port < 1024 || port > 65535) return fail("端口非法");
        // 同一协议的管理操作互斥：临界区含广播+收口（最长 60s），并发执行会造成 DB 状态后写者胜的漂移
        if (!operationGuard.tryLock(protocolName)) {
            return fail("协议[" + protocolName + "]有操作正在进行中，请稍后重试");
        }
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
        } finally {
            operationGuard.unlock(protocolName);
        }
    }

    @PostMapping("/{name}/update")
    public Map<String, Object> update(
            @PathVariable("name") String name,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return fail("请上传协议 jar 文件");
        if (!operationGuard.tryLock(name)) {
            return fail("协议[" + name + "]有操作正在进行中，请稍后重试");
        }
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
        } finally {
            operationGuard.unlock(name);
        }
    }

    @PostMapping("/{name}/bind/{port}")
    public Map<String, Object> bind(
            @PathVariable("name") String name,
            @PathVariable("port") int port) {
        if (!operationGuard.tryLock(name)) {
            return fail("协议[" + name + "]有操作正在进行中，请稍后重试");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "bind");
            body.put("protocolName", name);
            body.put("port", port);
            return doDistribute(body, true);
        } finally {
            operationGuard.unlock(name);
        }
    }

    /* ========== 纯指令类（不自动回滚，仅返回失败明细） ========== */

    @DeleteMapping("/bind/{port}")
    public Map<String, Object> unbind(@PathVariable("port") int port) {
        // unbind 请求仅携带端口：反查绑定的协议名后按协议维度加锁，保证与 unload/update 等操作互斥
        PortProtocolBinding binding = portBindingMapper.selectOne(
                new LambdaQueryWrapper<PortProtocolBinding>()
                        .eq(PortProtocolBinding::getPort, port));
        String lockKey = binding != null ? binding.getProtocolName() : "port:" + port;
        if (!operationGuard.tryLock(lockKey)) {
            return fail("协议[" + lockKey + "]有操作正在进行中，请稍后重试");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "unbind");
            body.put("port", port);
            return doDistribute(body, false);
        } finally {
            operationGuard.unlock(lockKey);
        }
    }

    @PostMapping("/reload/{name}")
    public Map<String, Object> reload(@PathVariable("name") String name) {
        if (!operationGuard.tryLock(name)) {
            return fail("协议[" + name + "]有操作正在进行中，请稍后重试");
        }
        try {
            return doReload(name);
        } finally {
            operationGuard.unlock(name);
        }
    }

    private Map<String, Object> doReload(String name) {
        ProtocolJarRegistry existing = registryMapper.selectByName(name);
        if (existing == null) {
            return fail("协议[" + name + "]不存在");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "reload");
        body.put("protocolName", name);

        // 1. 广播 reload 到所有 ACCESS 节点
        //    iot-access 内部流程：从 DB 拉 jar → 加载 → register(syncRegister 写 DB=REGISTERED) → bindPort
        //    失败时节点自行回滚（unregister + syncUnload 写 DB=UNLOADED）
        List<NodeResult> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isBusinessSuccess).count();
        List<NodeResult> failedNodes = results.stream()
                .filter(r -> !r.isBusinessSuccess())
                .toList();

        // 2. admin 统一收口 DB status（多节点共享同一行 DB 记录，节点自行写入会互相覆盖）
        //    reload 是"启用"操作：不回滚已成功节点（nginx 会自动避开端口没开的失败节点）
        LocalDateTime now = LocalDateTime.now();
        // 至少一个节点成功 → 协议在系统层面可用（nginx 会自动避开失败节点）→ REGISTERED
        // 全部失败 → FAILED（协议完全不可用）
        boolean anySuccess = ok > 0;
        if (anySuccess) {
            // 有节点成功 → DB=REGISTERED（协议在系统层面可用）
            registryMapper.updateStatus(name, "REGISTERED", now);
            portBindingMapper.update(null,
                    new LambdaUpdateWrapper<PortProtocolBinding>()
                            .eq(PortProtocolBinding::getProtocolName, name)
                            .set(PortProtocolBinding::getEnabled, true)
                            .set(PortProtocolBinding::getUpdatedAt, now));
            log.info("协议[{}]重新启用: 成功 {}/{} 节点{}", name, ok, results.size(),
                    failedNodes.isEmpty() ? "" : "（失败节点: " +
                            failedNodes.stream().map(NodeResult::getNodeId).toList() + "）");
        } else {
            // 全部失败 → DB=FAILED（协议完全不可用）
            registryMapper.updateStatus(name, "FAILED", now);
            log.warn("协议[{}]重新启用: 全部 {} 节点失败", name, results.size());
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", anySuccess);
        r.put("total", results.size());
        r.put("successCount", ok);
        r.put("failureCount", results.size() - ok);
        r.put("results", results.stream().map(NodeResult::getResult).toList());
        putNodeDetail(r, results);
        if (anySuccess) {
            r.put("status", "REGISTERED");
            if (!failedNodes.isEmpty()) {
                r.put("warning", "部分节点启用失败，已成功节点保持运行（nginx 会自动避开失败节点）");
            }
        } else {
            r.put("status", "FAILED");
            r.put("reason", results.isEmpty()
                    ? "无可用 ACCESS 节点"
                    : "所有节点启用失败");
        }
        return r;
    }


    /**
     * 协议卸载
     * @param name 协议名称
     * @return 请求结果
     */
    @DeleteMapping("/{name}")
    public Map<String, Object> unload(@PathVariable("name") String name) {
        if (!operationGuard.tryLock(name)) {
            return fail("协议[" + name + "]有操作正在进行中，请稍后重试");
        }
        try {
            return doUnload(name);
        } finally {
            operationGuard.unlock(name);
        }
    }

    private Map<String, Object> doUnload(String name) {
        ProtocolJarRegistry existing = registryMapper.selectByName(name);
        if (existing == null) {
            return fail("协议[" + name + "]不存在");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "unload");
        body.put("protocolName", name);

        // 1. 广播卸载到所有 ACCESS 节点（运行时清理：关端口监听、踢连接、destroy Provider、close ClassLoader）
        List<NodeResult> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isBusinessSuccess).count();
        List<NodeResult> failedNodes = results.stream().filter(r -> !r.isBusinessSuccess()).toList();

        // 2. unload 是“目标状态”操作（期望最终态=UNLOADED）：admin 直接操作共享 DB 兜底，
        //    不依赖节点是否全部成功。成功节点已自行 syncUnload，这里幂等；
        //    全部失败/部分失败时由 admin 强制把 DB 协议置 UNLOADED、端口绑定置禁用。
        LocalDateTime now = LocalDateTime.now();
        registryMapper.updateStatus(name, "UNLOADED", now);
        portBindingMapper.update(null,
                new LambdaUpdateWrapper<PortProtocolBinding>()
                        .eq(PortProtocolBinding::getProtocolName, name)
                        .set(PortProtocolBinding::getEnabled, false)
                        .set(PortProtocolBinding::getUpdatedAt, now));
        if (results.isEmpty()) {
            log.warn("协议[{}]卸载: 无可用 ACCESS 节点，仅完成 DB 兜底（UNLOADED + 端口禁用），" +
                    "没有任何节点执行运行时卸载，存量连接仍在服务", name);
        } else {
            log.info("协议[{}]卸载: admin 已直接置 DB status=UNLOADED + 端口禁用（节点成功 {}/{}）",
                    name, ok, results.size());
        }

        // 3. 失败节点交给公用虚拟线程池异步立即重试一次：
        //    不阻塞本次响应；重试前会竞争协议操作锁，管理员发起新操作时自动让位；
        //    重试仍失败则仅记录 ERROR 日志 + 同步状态页标记，停止自动动作
        //    （正确性由 DB 目标态兜底，节点重启后必然收敛）

        Map<String, Object> r = new LinkedHashMap<>();
        // DB 目标状态（UNLOADED）已达成，操作视为成功
        r.put("success", true);
        r.put("status", "UNLOADED");
        r.put("total", results.size());
        r.put("successCount", ok);
        r.put("failureCount", results.size() - ok);
        r.put("results", results.stream().map(NodeResult::getResult).toList());
        // 节点级成败清单
        putNodeDetail(r, results);
        if (results.isEmpty()) {
            // 无任何可用节点（全部 DOWN/失联）：DB 目标态已写入，节点重启后不会加载该协议，
            // 但没有任何节点执行运行时卸载（存量连接仍在服务、数据仍在入库）——
            // 必须显式告知调用方，避免管理员误以为运行时已清理而直接删除 nginx 转发配置
            r.put("warning", "无可用 ACCESS 节点：DB 已标记 UNLOADED + 端口禁用，" +
                    "但没有任何节点执行运行时卸载（存量连接仍在服务），节点恢复上线/重启后才会收敛");
            r.put("noReachableNode", true);
        } else if (!failedNodes.isEmpty()) {
            unloadRetryService.submitAsyncRetry(name, body, failedNodes);
            r.put("warning", "部分节点运行时卸载未完成（"
                    + failedNodes.stream().map(NodeResult::getNodeId).toList()
                    + "），已提交后台立即重试（异步 1 次）；DB 已标记 UNLOADED + 端口禁用");
        }
        return r;
    }

    @DeleteMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable("name") String name) {
        if (!operationGuard.tryLock(name)) {
            return fail("协议[" + name + "]有操作正在进行中，请稍后重试");
        }
        try {
            return doPurge(name);
        } finally {
            operationGuard.unlock(name);
        }
    }

    private Map<String, Object> doPurge(String name) {
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
        r.put("successNodes", distResult.get("successNodes"));
        if (distResult.get("failedNodes") != null) {
            r.put("failedNodes", distResult.get("failedNodes"));
        }
        if (!Boolean.TRUE.equals(distResult.get("success"))) {
            r.put("warning", "节点清理未全部成功，但 DB 记录已删除");
        }
        return r;
    }

    /* ========== 公共分发 + 补偿回滚 ========== */

    private Map<String, Object> doDistribute(Map<String, Object> body, boolean compensable) {
        List<NodeResult> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isBusinessSuccess).count();
        // 无可用节点（results 为空）视为失败，避免误判为"全部成功"
        boolean hasFailure = results.isEmpty() || ok < results.size();
        List<NodeResult> successNodes = results.stream()
                .filter(NodeResult::isBusinessSuccess)
                .toList();

        Map<String, Object> compensation = null;
        if (compensable && hasFailure && !successNodes.isEmpty()) {
            Map<String, Object> rollbackBody = buildRollbackBody(body);
            if (rollbackBody != null) {
                log.warn("协议操作部分节点失败，触发补偿回滚: mode={}, successCount={}, failureCount={}",
                        body.get("mode"), ok, results.size() - ok);
                List<NodeResult> compResults = broadcast.broadcastExplicit(
                        successNodes.stream().map(NodeResult::getNode).collect(Collectors.toList()),
                        "POST", INTERNAL_DISTRIBUTE_PATH, rollbackBody);
                long compOk = compResults.stream().filter(NodeResult::isBusinessSuccess).count();
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
        putNodeDetail(r, results);
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

    /**
     * 提取节点失败原因：优先 HTTP 层异常（超时/连接拒绝），
     * 其次节点业务响应中的 reason，最后退化为 HTTP 状态码。
     */
    private String extractFailReason(NodeResult nr) {
        Object err = nr.getResult().get("error");
        if (err != null) {
            return err.toString();
        }
        Object reason = nr.getBusinessResult().get("reason");
        if (reason != null) {
            return reason.toString();
        }
        int status = nr.getStatus();
        return status > 0 ? ("HTTP " + status) : "未知错误";
    }

    /**
     * 将节点级成败清单写入响应（successNodes / failedNodes 含失败原因）。
     * 所有分发类操作统一输出，供前端在「已加载协议」面板头部逐行展示，
     * 避免前端解析嵌套的 results[].response JSON。
     */
    private void putNodeDetail(Map<String, Object> r, List<NodeResult> results) {
        r.put("successNodes", results.stream()
                .filter(NodeResult::isBusinessSuccess)
                .map(NodeResult::getNodeId)
                .toList());
        List<Map<String, Object>> failedNodeDetails = results.stream()
                .filter(nr -> !nr.isBusinessSuccess())
                .map(nr -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("nodeId", nr.getNodeId());
                    f.put("reason", extractFailReason(nr));
                    return f;
                })
                .toList();
        if (!failedNodeDetails.isEmpty()) {
            r.put("failedNodes", failedNodeDetails);
        }
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
                reg.setCreatedAt(now);
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

        long ok = results.stream().filter(NodeResult::isBusinessSuccess).count();
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
            putNodeDetail(r, results);
            r.put("status", "REGISTERED");
            return r;
        }

        // 有失败：保守回滚已成功节点
        List<NodeResult> successNodes = results.stream().filter(NodeResult::isBusinessSuccess).toList();

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
            long compOk = compResults.stream().filter(NodeResult::isBusinessSuccess).count();
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
        putNodeDetail(r, results);
        r.put("status", "FAILED");
        if (compensation != null) {
            r.put("compensation", compensation);
        }
        return r;
    }

}
