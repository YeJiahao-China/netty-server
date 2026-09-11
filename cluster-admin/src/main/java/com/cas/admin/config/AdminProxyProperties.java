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

    /**
     * 广播/代理读超时（毫秒）。
     * 约束：必须大于 iot-access 节点处理一次分发指令的最长耗时
     * （协议卸载 = 每端口 5s 并行关闭 + destroy + ClassLoader 关闭，
     * 协议上传 = DB 拉 jar + probe + 加载 + 绑定端口），
     * 否则节点仍在正常执行就会被误判为失败，触发无意义的补偿/重试。
     * 与 application.yml 中 admin.proxy.read-timeout-ms 保持一致（默认 60s）。
     */
    private int readTimeoutMs = 60000;
}
