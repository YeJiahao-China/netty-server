package com.cas.admin.api;

import com.cas.admin.cluster.NodeBroadcastClient;
import com.cas.admin.cluster.NodeType;
import com.cas.admin.common.ApiResponse;
import com.cas.admin.entity.PortProtocolBinding;
import com.cas.admin.entity.ProtocolJarRegistry;
import com.cas.admin.mapper.PortProtocolBindingMapper;
import com.cas.admin.mapper.ProtocolJarRegistryMapper;
import com.cas.admin.proxy.AccessProxyClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 协议热插拔 API（管理中心视角）。
 * <ul>
 *   <li>GET /protocols*：代理到 primary iot-access（同库一致）</li>
 *   <li>POST /protocols/upload、POST /protocols/{name}/update：multipart 接收 jar → base64 → 广播到所有 UP ACCESS 节点的"落地接口" `/protocols/internal/distribute`</li>
 *   <li>POST /protocols/reload/{name}、POST /protocols/{name}/bind/{port}、DELETE /protocols/bind/{port}、DELETE /protocols/{name}、DELETE /protocols/{name}/purge：广播所有 UP ACCESS 节点</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/protocols")
@RequiredArgsConstructor
public class ProtocolAdminController {

    private final AccessProxyClient proxy;
    private final NodeBroadcastClient broadcast;
    private final ProtocolJarRegistryMapper registryMapper;
    private final PortProtocolBindingMapper portBindingMapper;

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
            m.put("failureDetail", p.getFailureDetail());
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

    /* ========== 协议上传（新增）：jar → base64 → 所有 ACCESS 节点落地并加载 ========== */

    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("port") int port,
            @RequestParam("protocolName") String protocolName) {
        if (file == null || file.isEmpty()) return ApiResponse.fail("请上传协议 jar 文件");
        if (protocolName == null || protocolName.isBlank()) return ApiResponse.fail("protocolName 不能为空");
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "upload");
            body.put("protocolName", protocolName);
            body.put("port", port);
            body.put("fileName", file.getOriginalFilename());
            body.put("jarBase64", base64);
            return doDistribute(body);
        } catch (Exception e) {
            return ApiResponse.fail("上传失败: " + e.getMessage());
        }
    }

    /* ========== 协议更新（已有协议覆盖新 jar） ========== */

    @PostMapping("/{name}/update")
    public Map<String, Object> update(
            @PathVariable("name") String name,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return ApiResponse.fail("请上传协议 jar 文件");
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "update");
            body.put("protocolName", name);
            body.put("fileName", file.getOriginalFilename());
            body.put("jarBase64", base64);
            return doDistribute(body);
        } catch (Exception e) {
            return ApiResponse.fail("更新失败: " + e.getMessage());
        }
    }

    /* ========== 纯指令类（无文件）：广播所有 ACCESS 节点 ========== */

    @PostMapping({"/reload", "/reload/all"})
    public Map<String, Object> reloadAll() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "reload");
        body.put("protocolName", "all");
        return doDistribute(body);
    }

    @PostMapping("/reload/{name}")
    public Map<String, Object> reload(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "reload");
        body.put("protocolName", name);
        return doDistribute(body);
    }

    @PostMapping("/{name}/bind/{port}")
    public Map<String, Object> bind(@PathVariable("name") String name, @PathVariable("port") int port) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "bind");
        body.put("protocolName", name);
        body.put("port", port);
        return doDistribute(body);
    }

    @DeleteMapping("/bind/{port}")
    public Map<String, Object> unbind(@PathVariable("port") int port) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "unbind");
        body.put("port", port);
        return doDistribute(body);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> unload(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "unload");
        body.put("protocolName", name);
        return doDistribute(body);
    }

    @DeleteMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "purge");
        body.put("protocolName", name);
        return doDistribute(body);
    }

    /* ========== 公共：广播分发落地结果聚合 ========== */

    private Map<String, Object> doDistribute(Map<String, Object> body) {
        List<Map<String, Object>> results = broadcast.broadcast(
                NodeType.ACCESS.name(), "POST", "/protocols/internal/distribute", body);
        long ok = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        Map<String, Object> r = ApiResponse.ok();
        r.put("total", results.size());
        r.put("successCount", ok);
        r.put("failureCount", results.size() - ok);
        r.put("results", results);
        return r;
    }
}
