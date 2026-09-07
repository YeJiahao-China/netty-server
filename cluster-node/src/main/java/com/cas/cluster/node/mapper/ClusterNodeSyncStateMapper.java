package com.cas.cluster.node.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.cluster.node.entity.ClusterNodeSyncState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集群节点协议同步状态 Mapper。
 */
public interface ClusterNodeSyncStateMapper extends BaseMapper<ClusterNodeSyncState> {

    /**
     * 按节点 + 协议 upsert 同步状态。
     */
    @Insert({
            "INSERT INTO cluster_node_sync_state(node_id, protocol_name, sync_state, detail, last_sync_time, updated_at) ",
            "VALUES(#{nodeId}, #{protocolName}, #{syncState}, #{detail}, #{lastSyncTime}, #{updatedAt}) ",
            "ON CONFLICT (node_id, protocol_name) DO UPDATE SET ",
            "  sync_state = EXCLUDED.sync_state, ",
            "  detail = EXCLUDED.detail, ",
            "  last_sync_time = EXCLUDED.last_sync_time, ",
            "  updated_at = EXCLUDED.updated_at"
    })
    int upsert(ClusterNodeSyncState state);

    /**
     * 批量查询某协议在所有节点上的状态。
     */
    @Select("SELECT * FROM cluster_node_sync_state WHERE protocol_name = #{protocolName} ORDER BY node_id")
    List<ClusterNodeSyncState> selectByProtocol(@Param("protocolName") String protocolName);

    /**
     * 批量查询某节点上所有协议的状态。
     */
    @Select("SELECT * FROM cluster_node_sync_state WHERE node_id = #{nodeId} ORDER BY protocol_name")
    List<ClusterNodeSyncState> selectByNode(@Param("nodeId") String nodeId);

    /**
     * 把某节点所有协议标为 PENDING（节点离线或待同步）。
     */
    @Update("UPDATE cluster_node_sync_state SET sync_state = 'PENDING', updated_at = #{ts} WHERE node_id = #{nodeId}")
    int markPendingByNode(@Param("nodeId") String nodeId, @Param("ts") LocalDateTime ts);
}
