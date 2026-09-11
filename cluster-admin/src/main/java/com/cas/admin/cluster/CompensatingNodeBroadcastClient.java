package com.cas.admin.cluster;

import com.cas.admin.service.PrimaryNodeRouter;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import jakarta.annotation.Resource;
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
 * 增强版节点广播客户端。
 *
 * <p>与现有 {@link NodeBroadcastClient} 相比，本类返回结构化的 {@link NodeResult}，
 * 方便调用方识别哪些节点成功、哪些失败，从而对部分失败场景执行补偿回滚。</p>
 *
 * <p>本类作为新增组件存在，不修改现有 {@link NodeBroadcastClient} 代码。</p>
 */
@Slf4j
@Component
public class CompensatingNodeBroadcastClient {

    /** 节点业务响应解析用（线程安全，静态单例避免每次解析重复创建） */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final RestClient restClient;
    private final PrimaryNodeRouter router;

    @Resource
    private ClusterNodeService nodeService;

    /**
     * 注入 cluster-admin 中已定义的 {@code RestClient.Builder} bean（{@code nodeRestClientBuilder}），
     * 避免依赖不存在的 {@link RestClient} 单例 bean。
     */
    public CompensatingNodeBroadcastClient(RestClient.Builder restClientBuilder, PrimaryNodeRouter router) {
        this.restClient = restClientBuilder.build();
        this.router = router;
    }

    public List<NodeResult> broadcast(String nodeType, String method, String path, Object body) {
        List<ClusterNode> targets = nodeService.listUpByType(nodeType);
        return broadcastExplicit(targets, method, path, body);
    }

    public List<NodeResult> broadcastExplicit(List<ClusterNode> targets, String method, String path, Object body) {
        if (targets == null || targets.isEmpty()) {
            return new ArrayList<>();
        }
        return targets.parallelStream()
                .map(n -> forward(n, method, path, body))
                .toList();
    }

    private NodeResult forward(ClusterNode node, String method, String path, Object body) {
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
        return new NodeResult(node, r);
    }

    /**
     * 单个节点的广播结果。
     */
    public static class NodeResult {
        private final ClusterNode node;
        private final Map<String, Object> result;

        public NodeResult(ClusterNode node, Map<String, Object> result) {
            this.node = node;
            this.result = result;
        }

        public ClusterNode getNode() {
            return node;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        public boolean isSuccess() {
            return Boolean.TRUE.equals(result.get("success"));
        }

        /**
         * 判断业务是否成功：HTTP 成功 + response 业务体 success=true。
         * response 是 iot-access 返回的 JSON 字符串，其中 success 字段才是真正的业务结果。
         */
        public boolean isBusinessSuccess() {
            if (!isSuccess()) return false;
            Object response = result.get("response");
            if (response == null) return false;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> bizResult = MAPPER.readValue(response.toString(), Map.class);
                return Boolean.TRUE.equals(bizResult.get("success"));
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 解析业务结果（response JSON 字符串 → Map）。
         * HTTP 失败或 JSON 解析失败时返回空 Map。
         */
        public Map<String, Object> getBusinessResult() {
            Object response = result.get("response");
            if (response == null) return Map.of();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> bizResult = MAPPER.readValue(response.toString(), Map.class);
                return bizResult;
            } catch (Exception e) {
                return Map.of();
            }
        }

        public int getStatus() {
            Object status = result.get("status");
            return status instanceof Number n ? n.intValue() : 0;
        }

        public String getNodeId() {
            return node.getNodeId();
        }
    }
}
