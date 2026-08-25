package com.cas.access.netty.protocol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 协议模块配置。
 *
 * <p>对应 application.yml：
 * <pre>
 * netty:
 *   server:
 *     protocol:
 *       jar-dir: ./protocols
 *       auto-load-on-startup: true
 * </pre>
 *
 * <p>注：端口→协议的映射已迁移到 DB（{@code port_protocol_binding} 表），
 * 由 {@link ProtocolRegistry} 通过 {@code PortBindingStore} 管理，不再在 yml 配置。
 *
 * @author yjh_c
 */
@Data
@Component
@ConfigurationProperties(prefix = "netty.server.protocol")
public class ProtocolProperties {

    /** 协议 jar 扫描目录，默认 ./protocols */
    private String jarDir = "./protocols";

    /** 启动时是否自动扫描一次外部协议 jar */
    private boolean autoLoadOnStartup = true;
}
