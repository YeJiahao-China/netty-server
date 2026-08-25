package com.cas.access.netty.protocol;

import java.util.List;
import java.util.Map;

/**
 * 协议配置持久化 SPI。
 *
 * <p>由 {@code netty-core} 的 {@code ProtocolBootstrap} 在启动时调用，
 * 由 {@code netty-admin} 的 {@code ProtocolJarRegistryService} 实现。
 *
 * <p>这种「接口反转依赖」避免了 netty-core 反向依赖 netty-admin。
 *
 * @author yjh_c
 */
public interface ProtocolStore {

    /**
     * 获取所有未删除且活跃的外部协议记录。
     *
     * @return 协议名称 -> jar 路径映射
     */
    Map<String, String> getActiveExternalProtocols();

    /**
     * 获取指定协议绑定的所有启用端口。
     *
     * @param protocolName 协议名称
     * @return 端口列表
     */
    List<Integer> getEnabledPortsByProtocol(String protocolName);
}