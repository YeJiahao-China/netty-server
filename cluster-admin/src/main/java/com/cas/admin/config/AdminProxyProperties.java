package com.cas.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理中心"代理/广播"配置。
 */
@Data
@ConfigurationProperties(prefix = "admin.proxy")
public class AdminProxyProperties {

    /** 默认主 iot-access（同类查询/同库统计从此地址代理；空则从 cluster_node 取 UP 节点） */
    private String accessPrimary = "http://127.0.0.1:2310";

    /** 默认主 iot-parser（同类查询代理） */
    private String parserPrimary = "http://127.0.0.1:2311";

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;
}
