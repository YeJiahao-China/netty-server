package com.cas.admin.cluster;

import com.cas.admin.service.PrimaryNodeRouter;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协调节点扇出：把同一请求并行发到目标类型所有 UP 节点，逐节点聚合结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeBroadcastClient {

    private final RestClient restClient;
    private final PrimaryNodeRouter router; // 未使用，仅保证初始化顺序（依赖已全注入）

    @Resource
    private ClusterNodeService nodeService;

    public List<Map<String, Object>> broadcast(String nodeType, String method, String path, Object body) {
        List<ClusterNode> targets = nodeService.listUpByType(nodeType);
        if (targets.isEmpty()) return new ArrayList<>();
        return targets.parallelStream().map(n -> forward(n, method, path, body)).toList();
    }

    public List<Map<String, Object>> broadcastExplicit(List<ClusterNode> targets, String method, String path, Object body) {
        if (targets == null || targets.isEmpty()) return new ArrayList<>();
        return targets.parallelStream().map(n -> forward(n, method, path, body)).toList();
    }

    private Map<String, Object> forward(ClusterNode node, String method, String path, Object body) {
        String url = "http://" + node.getHost() + ":" + node.getPort() + path;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodeId", node.getNodeId());
        r.put("host", node.getHost());
        r.put("port", node.getPort());
        try {
            HttpMethod m = HttpMethod.valueOf(method.toUpperCase());
            RestClient.RequestBodySpec spec = restClient.method(m).uri(url);
            if (body != null && m != HttpMethod.GET && m != HttpMethod.DELETE) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            ResponseEntity<String> resp = spec.retrieve().toEntity(String.class);
            r.put("status", resp.getStatusCode().value());
            r.put("success", resp.getStatusCode().is2xxSuccessful());
            r.put("response", resp.getBody() == null ? "" : resp.getBody());
        } catch (RestClientResponseException e) {
            r.put("status", e.getStatusCode().value());
            r.put("success", false);
            r.put("response", e.getResponseBodyAsString() == null ? "" : e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("广播到节点失败: nodeId={}, url={}, err={}", node.getNodeId(), url, e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
        }
        return r;
    }
}
