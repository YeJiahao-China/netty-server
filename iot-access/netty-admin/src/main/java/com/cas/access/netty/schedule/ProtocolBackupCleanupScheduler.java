package com.cas.access.netty.schedule;

import com.cas.access.netty.protocol.ProtocolBackupProperties;
import com.cas.access.netty.protocol.ProtocolProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 协议 jar 备份文件定时清理。
 *
 * <p>根据 {@link ProtocolBackupProperties} 配置，按保留数量和保留天数双重限制清理
 * {@code protocols/.backup/} 目录，避免 update 操作产生过多历史 jar 导致磁盘无限增长。</p>
 */
@Slf4j
@Component
public class ProtocolBackupCleanupScheduler {

    @Resource
    private ProtocolProperties properties;

    @Resource
    private ProtocolBackupProperties backupProperties;

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        if (!backupProperties.isEnabled()) {
            log.debug("协议备份清理已禁用");
            return;
        }
        Path backupDir = Paths.get(properties.getJarDir(), ".backup").toAbsolutePath();
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        int maxCount = backupProperties.getMaxBackupsPerProtocol();
        int maxAgeDays = backupProperties.getMaxBackupAgeDays();
        long nowMillis = System.currentTimeMillis();
        long ageThreshold = maxAgeDays > 0
                ? Instant.now().minus(maxAgeDays, ChronoUnit.DAYS).toEpochMilli()
                : 0;

        try (Stream<Path> stream = Files.list(backupDir)) {
            Map<String, List<Path>> byProtocol = stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .filter(p -> extractProtocolName(p) != null)
                    .collect(Collectors.groupingBy(this::extractProtocolName));

            int deleted = 0;
            for (Map.Entry<String, List<Path>> entry : byProtocol.entrySet()) {
                List<Path> sorted = entry.getValue().stream()
                        .sorted(Comparator.comparing(this::lastModified).reversed())
                        .toList();

                for (int i = 0; i < sorted.size(); i++) {
                    Path p = sorted.get(i);
                    boolean exceedCount = maxCount > 0 && i >= maxCount;
                    boolean exceedAge = maxAgeDays > 0 && lastModified(p) < ageThreshold;
                    if (exceedCount || exceedAge) {
                        try {
                            Files.deleteIfExists(p);
                            deleted++;
                            log.info("清理过期协议备份[{}]: {}", exceedAge ? "超期" : "超量", p);
                        } catch (IOException e) {
                            log.warn("删除备份文件失败: {}, err={}", p, e.getMessage());
                        }
                    }
                }
            }
            log.info("协议备份清理完成，共删除 {} 个过期文件 (maxCount={}, maxAgeDays={})",
                    deleted, maxCount, maxAgeDays);
        } catch (IOException e) {
            log.warn("扫描备份目录失败: {}", e.getMessage());
        }
    }

    private String extractProtocolName(Path path) {
        String fn = path.getFileName().toString();
        int idx = fn.lastIndexOf('-');
        if (idx <= 0) return null;
        return fn.substring(0, idx);
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
