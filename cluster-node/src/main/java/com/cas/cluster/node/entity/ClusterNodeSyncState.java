package com.cas.cluster.node.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 集群节点协议同步状态。
 *
 * <p>按 node_id + protocol_name 维度记录每个节点上各协议的实际同步状态，
 * 供 cluster-admin 页面展示不一致告警，也供离线节点恢复后自检。</p>
 */
@Data
@TableName("cluster_node_sync_state")
public class ClusterNodeSyncState {

    @TableId
    private String nodeId;

    private String protocolName;

    /** SYNCED / OUT_OF_SYNC / PENDING / UNKNOWN */
    private String syncState;

    /** 状态明细，如错误原因或操作模式 */
    private String detail;

    private LocalDateTime lastSyncTime;

    private LocalDateTime updatedAt;
}
