package com.cas.access.netty.protocol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 协议 jar 备份清理配置。
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * netty:
 *   server:
 *     protocol:
 *       backup:
 *         enabled: true
 *         max-backups-per-protocol: 5
 *         max-backup-age-days: 7
 * </pre>
 *
 * <p>清理策略：同时满足数量和天数限制。若某项配置为 0 或负数，则该项不生效。
 * 默认保留最近 5 个备份且不超过 7 天。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "netty.server.protocol.backup")
public class ProtocolBackupProperties {

    /** 是否启用定时清理 */
    private boolean enabled = true;

    /** 每个协议最多保留几个备份 */
    private int maxBackupsPerProtocol = 5;

    /** 备份文件最大保留天数 */
    private int maxBackupAgeDays = 7;
}
