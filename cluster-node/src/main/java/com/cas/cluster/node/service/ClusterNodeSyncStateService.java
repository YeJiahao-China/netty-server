package com.cas.cluster.node.service;

import com.cas.cluster.node.entity.ClusterNodeSyncState;
import com.cas.cluster.node.mapper.ClusterNodeSyncStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集群节点协议同步状态服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterNodeSyncStateService {

    private final ClusterNodeSyncStateMapper mapper;

    /**
     * 上报单个协议的同步状态。
     */
    public void report(String nodeId, String protocolName, String syncState, String detail) {
        ClusterNodeSyncState state = new ClusterNodeSyncState();
        state.setNodeId(nodeId);
        state.setProtocolName(protocolName);
        state.setSyncState(syncState);
        state.setDetail(detail);
        LocalDateTime now = LocalDateTime.now();
        state.setLastSyncTime(now);
        state.setUpdatedAt(now);
        try {
            mapper.upsert(state);
            log.debug("协议同步状态已上报: nodeId={}, protocol={}, state={}", nodeId, protocolName, syncState);
        } catch (Exception e) {
            log.warn("协议同步状态上报失败: nodeId={}, protocol={}, err={}", nodeId, protocolName, e.getMessage());
        }
    }

    /**
     * 查询某协议在所有节点上的状态。
     */
    public List<ClusterNodeSyncState> listByProtocol(String protocolName) {
        return mapper.selectByProtocol(protocolName);
    }

    /**
     * 查询某节点上所有协议的状态。
     */
    public List<ClusterNodeSyncState> listByNode(String nodeId) {
        return mapper.selectByNode(nodeId);
    }

    /**
     * 列出所有记录。
     */
    public List<ClusterNodeSyncState> listAll() {
        return mapper.selectList(null);
    }

    /**
     * 把节点下所有协议标为 PENDING。
     */
    public void markPending(String nodeId) {
        try {
            mapper.markPendingByNode(nodeId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("标记节点 PENDING 失败: nodeId={}, err={}", nodeId, e.getMessage());
        }
    }
}
