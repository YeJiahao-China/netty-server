package com.cas.cluster.node.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集群节点配置（prefix = cluster.node）。
 * <p>各 app 通过 classpath:cluster-node.properties 提供 type（避免改动现有 application.yml）。</p>
 */
@Data
@ConfigurationProperties(prefix = "cluster.node")
public class ClusterNodeProperties {

    /** 节点类型：ACCESS / PARSER（未配置则跳过注册，本节点不出现在节点列表） */
    private String type;

    /** 节点对外 IP；为空则自动探测本机首个非回环 IPv4 */
    private String host;

    /** 节点显示名；为空则用 host:port */
    private String name;
}
