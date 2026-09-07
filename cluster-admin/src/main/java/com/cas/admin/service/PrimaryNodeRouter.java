package com.cas.admin.service;

import com.cas.admin.common.NodeStatus;
import com.cas.admin.common.NodeType;
import com.cas.admin.config.AdminProxyProperties;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * "同类查询类请求"应该代理到哪个节点的选择器。
 * <p>原则：优先使用配置的 primary，但必须验证该节点在 cluster_node 表中状态为 UP；
 * 若配置未填写、或该节点不存在/已 DOWN，则从 cluster_node 选第一台 UP 的同类型节点兜底。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class PrimaryNodeRouter {

    private final AdminProxyProperties props;
    private final ClusterNodeService service;

    /**
     * 解析可用的 iot-access 基地址。
     *
     * @return http://host:port，没有可用节点时返回 null
     */
    public String resolveAccessBase() {
        return resolve(NodeType.ACCESS.name(), props.getAccessPrimary());
    }

    /**
     * 解析可用的 iot-parser 基地址。
     *
     * @return http://host:port，没有可用节点时返回 null
     */
    public String resolveParserBase() {
        return resolve(NodeType.PARSER.name(), props.getParserPrimary());
    }

    /**
     * 通用解析逻辑：
     * 1. 配置了 primary → 从 URL 提取 host:port 作为 nodeId，查 DB 验证状态
     * 2. 节点存在且 UP → 返回配置地址
     * 3. 节点不存在或 DOWN → warn 并从 DB 选 UP 节点兜底
     * 4. 无可用 UP 节点 → 返回 null
     */
    private String resolve(String nodeType, String configuredPrimary) {
        if (configuredPrimary != null && !configuredPrimary.isBlank()) {
            String nodeId = extractNodeId(configuredPrimary);
            ClusterNode node = service.getByNodeId(nodeId);
            if (node != null && NodeStatus.UP.name().equals(node.getStatus())) {
                return stripSlash(configuredPrimary);
            }
        }
        // 从 DB 选一台 UP 的同类型节点兜底
        List<ClusterNode> up = service.listUpByType(nodeType);
        if (!up.isEmpty()) return toBase(up.get(0));
        return null;
    }

    /**
     * 从 http://127.0.0.1:2310 提取 127.0.0.1:2310（即 cluster_node.node_id）
     */
    private static String extractNodeId(String url) {
        String s = stripSlash(url);
        if (s.startsWith("http://")) return s.substring(7);
        if (s.startsWith("https://")) return s.substring(8);
        return s;
    }

    private static String toBase(ClusterNode node) {
        return "http://" + node.getHost() + ":" + node.getPort();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
