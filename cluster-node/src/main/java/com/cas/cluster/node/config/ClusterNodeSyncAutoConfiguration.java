package com.cas.cluster.node.config;

import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Import;

/**
 * 节点协议同步状态自动装配。
 *
 * <p>独立 AutoConfiguration，避免修改现有 {@link ClusterNodeAutoConfiguration}。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(ClusterNodeAutoConfiguration.class)
@Import(ClusterNodeSyncStateService.class)
public class ClusterNodeSyncAutoConfiguration {
}
