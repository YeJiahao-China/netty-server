package com.cas.cluster.node.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.cluster.node.entity.ClusterNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 集群节点 Mapper。
 * <p>无 XML：通用查询走 BaseMapper；upsert/心跳/扫描走 @Insert/@Update 注解 SQL。</p>
 */
public interface ClusterNodeMapper extends BaseMapper<ClusterNode> {

    /** 启动注册：按 node_id 唯一键 upsert（PostgreSQL ON CONFLICT）。单参数 POJO，属性直接以 #{field} 引用 */
    @Insert({
            "INSERT INTO cluster_node(node_id, node_type, host, port, node_name, status, last_heartbeat, registered_at) ",
            "VALUES(#{nodeId}, #{nodeType}, #{host}, #{port}, #{nodeName}, #{status}, #{lastHeartbeat}, #{registeredAt}) ",
            "ON CONFLICT (node_id) DO UPDATE SET ",
            "  node_type = EXCLUDED.node_type, ",
            "  host = EXCLUDED.host, ",
            "  port = EXCLUDED.port, ",
            "  node_name = EXCLUDED.node_name, ",
            "  status = EXCLUDED.status, ",
            "  last_heartbeat = EXCLUDED.last_heartbeat, ",
            "  registered_at = EXCLUDED.registered_at"
    })
    int upsert(ClusterNode node);

    /** 心跳：更新自身最近心跳时间 + 复位为 UP */
    @Update("UPDATE cluster_node SET last_heartbeat = #{ts}, status = 'UP' WHERE node_id = #{id}")
    int heartbeat(@Param("id") String id, @Param("ts") LocalDateTime ts);

    /** 失效扫描：把超时未心跳的 UP 节点标 DOWN（幂等，多节点并发执行无副作用） */
    @Update("UPDATE cluster_node SET status = 'DOWN' WHERE status = 'UP' AND last_heartbeat < #{threshold}")
    int sweepStale(@Param("threshold") LocalDateTime threshold);

    /** 优雅停机：把自身标 DOWN */
    @Update("UPDATE cluster_node SET status = 'DOWN' WHERE node_id = #{id}")
    int markDown(@Param("id") String id);
}
