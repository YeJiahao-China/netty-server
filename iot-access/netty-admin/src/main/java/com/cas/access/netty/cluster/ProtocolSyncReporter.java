package com.cas.access.netty.cluster;

import com.cas.cluster.node.config.NodeIdentity;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 协议同步状态上报器。
 *
 * <p>在 {@link com.cas.access.netty.api.ProtocolV2Controller} 执行完 distribute 操作后，
 * 调用本类把本节点对该协议的同步状态写入共享 DB，供 cluster-admin 查询展示。</p>
 */
@Slf4j
@Component
public class ProtocolSyncReporter {

    @Resource
    private ClusterNodeSyncStateService syncStateService;

    @Resource
    private NodeIdentity nodeIdentity;

    /**
     * 上报协议操作结果作为同步状态。
     *
     * @param protocolName 协议名
     * @param mode         操作模式，如 upload / update / bind
     * @param result       操作结果 map（需含 success / reason 等）
     */
    public void report(String protocolName, String mode, Map<String, Object> result) {
        if (!nodeIdentity.isConfigured()) {
            log.debug("节点身份未配置，跳过协议同步状态上报");
            return;
        }
        boolean success = Boolean.TRUE.equals(result.get("success"));
        String state = success ? "SYNCED" : "OUT_OF_SYNC";
        String detail = success
                ? "mode=" + mode + ", success=true"
                : "mode=" + mode + ", success=false, reason=" + result.get("reason");
        syncStateService.report(nodeIdentity.getNodeId(), protocolName, state, detail);
    }

    /**
     * 上报无法识别的 mode 或异常状态。
     */
    public void reportUnknown(String protocolName, String detail) {
        if (!nodeIdentity.isConfigured()) return;
        syncStateService.report(nodeIdentity.getNodeId(), protocolName, "UNKNOWN", detail);
    }
}
