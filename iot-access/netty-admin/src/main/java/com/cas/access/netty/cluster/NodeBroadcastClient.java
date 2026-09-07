package com.cas.access.netty.cluster;

import com.cas.cluster.node.entity.ClusterNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协调节点扇出客户端：并发向多个兄弟节点转发同一请求，逐节点聚合结果。
 * <p>尽力而为：单节点失败不影响其它节点；4xx/5xx 也捕获响应体便于排障。</p>
 */
@Slf4j
@Component
public class NodeBroadcastClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final RestClient client;

    public NodeBroadcastClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 并发向多个节点转发同一请求，逐节点收集结果。
     */
    public List<Map<String, Object>> broadcast(List<ClusterNode> targets, String method, String path, Object body) {
        return targets.parallelStream()
                .map(node -> forward(node, method, path, body))
                .toList();
    }

    private Map<String, Object> forward(ClusterNode node, String method, String path, Object body) {
        String url = "http://" + node.getHost() + ":" + node.getPort() + path;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodeId", node.getNodeId());
        r.put("host", node.getHost());
        r.put("port", node.getPort());

        try {
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
            RestClient.RequestBodySpec spec = client.method(httpMethod).uri(url);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON);
                spec.body(body);
            }
            ResponseEntity<String> entity = spec.retrieve().toEntity(String.class);
            r.put("status", entity.getStatusCode().value());
            r.put("success", entity.getStatusCode().is2xxSuccessful());
            r.put("response", safeBody(entity.getBody()));
        } catch (RestClientResponseException e) {
            // 4xx/5xx：拿到上游响应体 + 状态码，标记失败
            r.put("status", e.getStatusCode().value());
            r.put("success", false);
            r.put("response", safeBody(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.warn("广播到节点失败: nodeId={}, url={}, error={}", node.getNodeId(), url, e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    private String safeBody(String body) {
        return body == null ? "" : body;
    }
}
