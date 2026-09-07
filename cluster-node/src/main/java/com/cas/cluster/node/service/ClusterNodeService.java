package com.cas.cluster.node.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.mapper.ClusterNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集群节点注册/心跳/查询服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterNodeService {

    private final ClusterNodeMapper mapper;

    /** 启动注册自身（upsert） */
    public void registerSelf(String nodeId, String type, String host, int port, String name) {
        ClusterNode node = new ClusterNode();
        node.setNodeId(nodeId);
        node.setNodeType(type);
        node.setHost(host);
        node.setPort(port);
        node.setNodeName(name);
        node.setStatus("UP");
        LocalDateTime now = LocalDateTime.now();
        node.setLastHeartbeat(now);
        node.setRegisteredAt(now);
        mapper.upsert(node);
        log.info("集群节点已注册: nodeId={}, type={}, host={}:{}", nodeId, type, host, port);
    }

    /** 心跳保活 */
    public void heartbeat(String nodeId) {
        mapper.heartbeat(nodeId, LocalDateTime.now());
    }

    /** 优雅停机标记 DOWN */
    public void markDown(String nodeId) {
        mapper.markDown(nodeId);
    }

    /** 失效扫描：超时未心跳的 UP 节点标 DOWN */
    public int sweepStale(int ttlSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(ttlSeconds);
        return mapper.sweepStale(threshold);
    }

    /** 按类型查全部节点（type 为空则全部） */
    public List<ClusterNode> listByType(String type) {
        LambdaQueryWrapper<ClusterNode> wrapper = new LambdaQueryWrapper<>();
        if (type != null && !type.isBlank()) {
            wrapper.eq(ClusterNode::getNodeType, type);
        }
        wrapper.orderByAsc(ClusterNode::getNodeType)
                .orderByAsc(ClusterNode::getNodeId);
        return mapper.selectList(wrapper);
    }

    /** 按类型查 UP 节点（广播目标） */
    public List<ClusterNode> listUpByType(String type) {
        LambdaQueryWrapper<ClusterNode> wrapper = new LambdaQueryWrapper<ClusterNode>()
                .eq(ClusterNode::getStatus, "UP");
        if (type != null && !type.isBlank()) {
            wrapper.eq(ClusterNode::getNodeType, type);
        }
        return mapper.selectList(wrapper);
    }

    /** 按 nodeId（host:port）查节点状态 */
    public ClusterNode getByNodeId(String nodeId) {
        return mapper.selectById(nodeId);
    }
}
