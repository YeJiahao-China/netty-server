package com.cas.cluster.node.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 集群节点注册表（iot-access / iot-parser 共享）。
 * <p>列名与字段名的下划线/驼峰映射由 mybatis-plus 的 map-underscore-to-camel-case 处理。</p>
 */
@Data
@TableName("cluster_node")
public class ClusterNode {

    /** 节点唯一键 host:port，手动输入（不走自增） */
    @TableId(value = "node_id", type = IdType.INPUT)
    private String nodeId;

    /** 节点类型 ACCESS / PARSER */
    private String nodeType;

    private String host;

    private Integer port;

    private String nodeName;

    /** 节点状态 UP / DOWN */
    private String status;

    private LocalDateTime lastHeartbeat;

    private LocalDateTime registeredAt;
}
