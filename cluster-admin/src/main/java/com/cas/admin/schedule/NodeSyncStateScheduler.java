package com.cas.admin.schedule;

import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeService;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 节点协议同步状态定时维护。
 *
 * <p>把 cluster_node 中 status=DOWN 的节点，在 cluster_node_sync_state 中所有协议标为 PENDING，
 * 让管理员在页面上看到“节点已失联，协议状态待确认”。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeSyncStateScheduler {

    private final ClusterNodeService nodeService;
    private final ClusterNodeSyncStateService syncStateService;

    /**
     * 每 30 秒扫描一次 DOWN 节点并标记 PENDING。
     */
    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    public void markDownNodesPending() {
        try {
            List<ClusterNode> downNodes = nodeService.listByType(null).stream()
                    .filter(n -> "DOWN".equals(n.getStatus()))
                    .toList();
            if (downNodes.isEmpty()) {
                return;
            }
            for (ClusterNode node : downNodes) {
                syncStateService.markPending(node.getNodeId());
                log.debug("节点[{}]已 DOWN，协议同步状态标为 PENDING", node.getNodeId());
            }
            log.info("已标记 {} 个 DOWN 节点的协议同步状态为 PENDING", downNodes.size());
        } catch (Exception e) {
            log.warn("标记 DOWN 节点 PENDING 失败: {}", e.getMessage());
        }
    }
}
