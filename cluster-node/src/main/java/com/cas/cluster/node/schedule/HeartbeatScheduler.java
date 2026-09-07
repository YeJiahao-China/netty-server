package com.cas.cluster.node.schedule;

import com.cas.cluster.node.config.NodeIdentity;
import com.cas.cluster.node.service.ClusterNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳保活 + 失效扫描。@EnableScheduling 由 ClusterNodeAutoConfiguration 开启。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatScheduler {

    private final ClusterNodeService service;
    private final NodeIdentity identity;

    /** 每 15s 更新自身心跳 */
    @Scheduled(fixedDelay = 15000L, initialDelay = 15000L)
    public void heartbeat() {
        if (!identity.isConfigured()) {
            return;
        }
        service.heartbeat(identity.getNodeId());
    }

    /** 每 30s 扫描失效节点（幂等，多节点并发安全） */
    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    public void sweep() {
        int n = service.sweepStale(45);
        if (n > 0) {
            log.info("集群失效节点扫描：标记 {} 个节点为 DOWN", n);
        }
    }
}
