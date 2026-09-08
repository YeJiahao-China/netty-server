//package com.cas.access.netty.service;
//
//import com.cas.access.netty.mapper.ProtocolJarSyncMapper;
//import com.cas.access.netty.protocol.ProtocolProperties;
//import jakarta.annotation.Resource;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.core.annotation.Order;
//import org.springframework.stereotype.Component;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.Map;
//
///**
// * 协议 jar 仓库同步服务。
// * <p>
// * 新节点启动时，从 {@code protocol_jar_registry} 表拉取所有 REGISTERED 状态的协议 jar 二进制，
// * 写入本地 jar 目录（{@code <jarDir>/<protocolName>.jar}），使后续 {@link com.cas.access.netty.bootstrap.ProtocolBootstrap} 能正常加载。
// * </p>
// * <p>
// * 运行顺序：{@code ProtocolJarSyncService}(@Order(0)) → {@code ProtocolBootstrap}(@Order(1))
// * </p>
// *
// * @author yjh_c
// */
//@Slf4j
//@Component
//@Order(0)
//public class ProtocolJarSyncService implements CommandLineRunner {
//
//    @Resource
//    private ProtocolJarSyncMapper syncMapper;
//
//    @Resource
//    private ProtocolProperties properties;
//
//    @Override
//    public void run(String... args) {
//        syncJarsFromDb();
//    }
//
//    /**
//     * 从 protocol_jar 表拉取所有 jar，写入本地 jar 目录。
//     * 如果本地已存在同名文件且大小一致则跳过。
//     */
//    private void syncJarsFromDb() {
//        try {
//            // 只同步 status=REGISTERED 且有 jar_bytes 的协议
//            List<Map<String, Object>> jars = syncMapper.selectRegisteredJars();
//            if (jars.isEmpty()) {
//                log.info("无 REGISTERED 状态的协议 jar，无需同步");
//                return;
//            }
//
//            String jarDir = properties.getJarDir();
//            Path dir = Paths.get(jarDir);
//            Files.createDirectories(dir);
//            int synced = 0;
//
//            for (Map<String, Object> row : jars) {
//                String protocolName = (String) row.get("name");
//                byte[] bytes = (byte[]) row.get("jar_bytes");
//                if (bytes == null) bytes = (byte[]) row.get("jarBytes");
//                if (protocolName == null || bytes == null) {
//                    log.warn("protocol_jar_registry 记录字段缺失，跳过: {}", row);
//                    continue;
//                }
//
//                // 文件名固定为 <protocolName>.jar，本地路径由 jarDir 配置决定
//                Path localPath = dir.resolve(protocolName + ".jar");
//
//                // 检查本地是否已存在该 jar 文件
//                if (Files.exists(localPath) && Files.size(localPath) == bytes.length) {
//                    log.debug("jar 已存在且大小一致，跳过: name={}, path={}", protocolName, localPath);
//                    continue;
//                }
//
//                // 写入本地
//                Files.write(localPath, bytes);
//                synced++;
//                log.info("从 DB 同步协议 jar: name={}, size={}KB, path={}",
//                        protocolName, bytes.length / 1024, localPath);
//            }
//
//            log.info("协议 jar 同步完成: 共 {} 个 REGISTERED jar，本次新写入 {} 个", jars.size(), synced);
//        } catch (Exception e) {
//            log.error("从 protocol_jar_registry 同步失败: {}", e.getMessage(), e);
//        }
//    }
//}
