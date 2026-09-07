package com.cas.cluster.node.runner;

import com.cas.cluster.node.config.NodeIdentity;
import com.cas.cluster.node.service.ClusterNodeService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把自身节点 upsert 到 cluster_node；优雅停机时标 DOWN。
 * <p>cluster.node.type 未配置则跳过（该进程不参与集群节点注册）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeRegistrar implements ApplicationRunner {

    private final ClusterNodeService service;
    private final NodeIdentity identity;

    @Override
    public void run(ApplicationArguments args) {
        if (!identity.isConfigured()) {
            log.warn("cluster.node.type 未配置，跳过节点注册（本节点不会出现在集群节点列表）");
            return;
        }
        service.registerSelf(
                identity.getNodeId(),
                identity.getProps().getType(),
                identity.getHost(),
                identity.getPort(),
                identity.getName()
        );
    }

    @PreDestroy
    public void onShutdown() {
        if (identity.isConfigured()) {
            try {
                service.markDown(identity.getNodeId());
                log.info("集群节点已标记下线: nodeId={}", identity.getNodeId());
            } catch (Exception e) {
                log.warn("节点下线标记失败: nodeId={}, error={}", identity.getNodeId(), e.getMessage());
            }
        }
    }
}
