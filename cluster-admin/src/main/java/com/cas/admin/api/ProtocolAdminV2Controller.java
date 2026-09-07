package com.cas.admin.api;

import com.cas.admin.cluster.CompensatingNodeBroadcastClient;
import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.admin.common.NodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
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

    /* ========== 上传 / 更新 / 绑定（需要补偿回滚） ========== */

    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("port") int port,
            @RequestParam("protocolName") String protocolName) {
        if (file == null || file.isEmpty()) return fail("请上传协议 jar 文件");
        if (protocolName == null || protocolName.isBlank()) return fail("protocolName 不能为空");
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "upload");
            body.put("protocolName", protocolName);
            body.put("port", port);
            body.put("fileName", file.getOriginalFilename());
            body.put("jarBase64", base64);
            return doDistribute(body, true);
        } catch (Exception e) {
            log.error("V2 upload 失败", e);
            return fail("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/{name}/update")
    public Map<String, Object> update(
            @PathVariable("name") String name,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return fail("请上传协议 jar 文件");
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "update");
            body.put("protocolName", name);
            body.put("fileName", file.getOriginalFilename());
            body.put("jarBase64", base64);
            return doDistribute(body, true);
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "purge");
        body.put("protocolName", name);
        return doDistribute(body, false);
    }

    /* ========== 公共分发 + 补偿回滚 ========== */

    private Map<String, Object> doDistribute(Map<String, Object> body, boolean compensable) {
        List<NodeResult> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", INTERNAL_DISTRIBUTE_PATH, body);

        long ok = results.stream().filter(NodeResult::isSuccess).count();
        boolean hasFailure = ok < results.size();
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
            r.put("reason", "部分节点执行失败");
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
}
