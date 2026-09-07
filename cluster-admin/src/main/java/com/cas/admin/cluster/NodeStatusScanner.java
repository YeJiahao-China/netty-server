package com.cas.admin.cluster;

import com.cas.admin.common.NodeStatus;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import com.cas.admin.common.NodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 定时扫描 cluster_node 表，检测状态变化后通过 SSE 推送到前端。
 * <p>
 * 后端 2s 查一次 DB，有变化才推送——多前端连接时 DB 只查一次，结果扇出到所有浏览器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeStatusScanner {

    private final ClusterNodeService service;
    private final NodeSseManager sseManager;

    private String lastNodesHash = "";
    private String lastOverviewHash = "";

    @Scheduled(fixedDelay = 2000)
    public void scan() {
        scanOverview();
        scanNodes();
    }

    private void scanOverview() {
        try {
            List<ClusterNode> accessNodes = service.listByType(NodeType.ACCESS.name());
            List<ClusterNode> parserNodes = service.listByType(NodeType.PARSER.name());
            long accessUp = accessNodes.stream().filter(n -> NodeStatus.UP.name().equals(n.getStatus())).count();
            long parserUp = parserNodes.stream().filter(n -> NodeStatus.UP.name().equals(n.getStatus())).count();
            int total = accessNodes.size() + parserNodes.size();
            long totalUp = accessUp + parserUp;

            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("success", true);
            overview.put("accessTotal", accessNodes.size());
            overview.put("accessUp", accessUp);
            overview.put("parserTotal", parserNodes.size());
            overview.put("parserUp", parserUp);
            overview.put("downCount", total - totalUp);
            overview.put("healthRate", total > 0 ? String.format("%.0f", (double) totalUp / total * 100) : "0");

            String hash = buildHash(overview);
            if (!Objects.equals(hash, lastOverviewHash)) {
                lastOverviewHash = hash;
                sseManager.sendToAll("overview", overview);
            }
        } catch (Exception e) {
            log.warn("扫描概览失败: {}", e.getMessage());
        }
    }

    private void scanNodes() {
        try {
            List<ClusterNode> accessNodes = service.listByType(NodeType.ACCESS.name());
            List<ClusterNode> parserNodes = service.listByType(NodeType.PARSER.name());
            List<Map<String, Object>> data = new ArrayList<>();
            for (ClusterNode n : accessNodes) data.add(toMap(n));
            for (ClusterNode n : parserNodes) data.add(toMap(n));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("data", data);

            String hash = buildHash(payload);
            if (!Objects.equals(hash, lastNodesHash)) {
                lastNodesHash = hash;
                sseManager.sendToAll("nodes", payload);
            }
        } catch (Exception e) {
            log.warn("扫描节点列表失败: {}", e.getMessage());
        }
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

    private String buildHash(Map<String, Object> map) {
        return String.valueOf(map);
    }
}
