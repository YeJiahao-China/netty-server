package com.cas.admin.api;

import com.cas.admin.cluster.BroadcastRequest;
import com.cas.admin.cluster.NodeBroadcastClient;
import com.cas.admin.cluster.NodeSseManager;
import com.cas.admin.common.NodeStatus;
import com.cas.admin.common.NodeType;
import com.cas.admin.common.ApiResponse;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理中心 - 集群节点接口 + 通用广播入口 + 健康探测。
 */
@Slf4j
@RestController
@RequestMapping("/cluster")
@RequiredArgsConstructor
public class ClusterNodeController {

    private final ClusterNodeService service;
    private final NodeBroadcastClient broadcast;
    private final RestClient.Builder nodeRestClientBuilder;
    private final NodeSseManager sseManager;

    /**
     * SSE 连接端点：前端建立 EventSource 后，后端检测到节点状态变化时自动推送。
     */
    @GetMapping(value = "/sse", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object sse(HttpServletRequest request, HttpServletResponse response) {
        // 防御 ERROR dispatch 或 async 不支持导致的 "Cannot start async"
        if (request.getDispatcherType() != DispatcherType.REQUEST || !request.isAsyncSupported()) {
            log.warn("SSE 请求状态异常，拒绝建立连接: dispatcherType={}, asyncSupported={}",
                    request.getDispatcherType(), request.isAsyncSupported());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("reason", "SSE 请求状态异常: " + request.getDispatcherType());
            return r;
        }
        return sseManager.register();
    }

    @GetMapping("/nodes")
    public Map<String, Object> nodes(@RequestParam(value = "type", required = false) String type) {
        List<ClusterNode> list = service.listByType(type);
        List<Map<String, Object>> data = new ArrayList<>();
        for (ClusterNode n : list) {
            data.add(toMap(n));
        }
        Map<String, Object> r = ApiResponse.ok();
        r.put("data", data);
        return r;
    }

    /**
     * 集群概览统计：ACCESS/PARSER 各自总数与 UP 数。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<ClusterNode> accessNodes = service.listByType(NodeType.ACCESS.name());
        List<ClusterNode> parserNodes = service.listByType(NodeType.PARSER.name());
        long accessUp = accessNodes.stream().filter(n -> NodeStatus.UP.name().equals(n.getStatus())).count();
        long parserUp = parserNodes.stream().filter(n -> NodeStatus.UP.name().equals(n.getStatus())).count();
        int total = accessNodes.size() + parserNodes.size();
        long totalUp = accessUp + parserUp;

        Map<String, Object> r = ApiResponse.ok();
        r.put("accessTotal", accessNodes.size());
        r.put("accessUp", accessUp);
        r.put("parserTotal", parserNodes.size());
        r.put("parserUp", parserUp);
        r.put("downCount", total - totalUp);
        r.put("healthRate", total > 0 ? String.format("%.0f", (double) totalUp / total * 100) : "0");
        return r;
    }

    /**
     * 主动健康探测：cluster-admin 直接 HTTP ping 节点的 host:port。
     */
    @GetMapping("/nodes/{nodeId}/health")
    public Map<String, Object> health(@PathVariable String nodeId) {
        ClusterNode node = service.getByNodeId(nodeId);
        if (node == null) return ApiResponse.fail("节点不存在: " + nodeId);
        String url = "http://" + node.getHost() + ":" + node.getPort() + "/";
        long start = System.currentTimeMillis();
        try {
            nodeRestClientBuilder.build()
                    .get().uri(url)
                    .retrieve()
                    .toBodilessEntity();
            long latency = System.currentTimeMillis() - start;
            log.info("健康探测: nodeId={}, url={}, latency={}ms", nodeId, url, latency);
            Map<String, Object> r = ApiResponse.ok();
            r.put("reachable", true);
            r.put("latencyMs", latency);
            return r;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // I/O 错误 = 节点不可达
            long latency = System.currentTimeMillis() - start;
            log.warn("健康探测失败: nodeId={}, url={}, latency={}ms, err={}", nodeId, url, latency, e.getMessage());
            Map<String, Object> r = ApiResponse.ok();
            r.put("reachable", false);
            r.put("latencyMs", latency);
            r.put("error", "连接失败: " + e.getMessage());
            return r;
        } catch (Exception e) {
            // HTTP 错误状态（404/500 等）= 节点可达但服务异常
            long latency = System.currentTimeMillis() - start;
            log.info("健康探测(节点可达但HTTP异常): nodeId={}, url={}, latency={}ms, err={}", nodeId, url, latency, e.getMessage());
            Map<String, Object> r = ApiResponse.ok();
            r.put("reachable", true);
            r.put("latencyMs", latency);
            return r;
        }
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@RequestBody BroadcastRequest req) {
        if (req.getPath() == null || req.getPath().isBlank() || !req.getPath().startsWith("/")) {
            return ApiResponse.fail("path 必须以 / 开头");
        }
        if (req.getMethod() == null || req.getMethod().isBlank()) {
            return ApiResponse.fail("method 不能为空");
        }
        List<Map<String, Object>> results = broadcast.broadcast(req.getNodeType(), req.getMethod(), req.getPath(), req.getBody());
        Map<String, Object> r = ApiResponse.ok();
        r.put("total", results.size());
        r.put("results", results);
        return r;
    }

    private Map<String, Object> toMap(ClusterNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", n.getNodeId());
        m.put("nodeType", n.getNodeType());
        m.put("host", n.getHost());
        m.put("port", n.getPort());
        m.put("nodeName", n.getNodeName());
        m.put("status", n.getStatus());
        m.put("lastHeartbeat", n.getLastHeartbeat());
        m.put("registeredAt", n.getRegisteredAt());
        return m;
    }
}
