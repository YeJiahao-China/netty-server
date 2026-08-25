package com.cas.access.netty.protocol;

import java.util.Map;

/**
 * 端口-协议绑定的持久化 SPI。
 *
 * <p>由 {@code netty-core} 的 {@code ProtocolRegistry} / {@code ProtocolBootstrap} 调用，
 * 由 {@code netty-admin} 的 {@code PortBindingService} 实现。
 *
 * <p>这种「接口反转依赖」避免了 netty-core 反向依赖 netty-admin。
 *
 * <p>实现方需保证：
 * <ul>
 *   <li>所有方法「写库失败不阻断运行时」，try-catch 后仅 warn 日志</li>
 *   <li>{@link #persistBind(int, String)} 是 upsert 语义（重复调用幂等）</li>
 * </ul>
 *
 * @author yjh_c
 */
public interface PortBindingStore {

    /**
     * 启动时加载所有 enabled=true 的绑定。
     *
     * @return port → protocolName 映射（空映射表示无启用绑定）
     */
    Map<Integer, String> loadEnabledBindings();

    /**
     * 持久化一个端口绑定（upsert）。
     * 若已存在则更新 protocol_name + enabled=true；否则插入。
     */
    void persistBind(int port, String protocolName);

    /**
     * 持久化解绑：标记 enabled=false（保留记录）。
     */
    void persistUnbind(int port);
}
