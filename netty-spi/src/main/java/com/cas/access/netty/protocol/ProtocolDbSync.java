package com.cas.access.netty.protocol;

/**
 * 协议 DB 同步 SPI。
 *
 * <p>由 {@code netty-core} 的 {@code ProtocolRegistry} 在协议注册/卸载时回调，
 * 由 {@code netty-admin} 的 {@code ProtocolJarRegistryService} 实现。
 *
 * <p>这种"接口反转依赖"避免了 netty-core 反向依赖 netty-admin。
 *
 * <p>所有方法均应保证「写库失败不阻断运行时」——实现方需自行 try-catch，
 * 仅记录 warn 日志，让 Registry 的内存状态始终是 source of truth。
 *
 * @author yjh_c
 */
public interface ProtocolDbSync {

    /**
     * 同步协议注册到数据库。
     * 同名协议：更新元数据；否则插入新记录。
     */
    void syncRegister(LoadedProtocol lp);

    /**
     * 同步协议卸载：通常做逻辑删除（deleted=1）+ active=false。
     */
    void syncUnload(String name);
}
