package com.cas.access.netty.protocol;

import io.netty.channel.ChannelHandler;

/**
 * 协议接入提供者 SPI 接口。
 *
 * 使用方式：
 *  - 内置实现：标注 {@link org.springframework.stereotype.Component}，由 Spring 自动扫描注册
 *  - 外部 jar 实现：在 {@code META-INF/services/com.cas.access.netty.protocol.ProtocolDecoderProvider}
 *    文件中登记实现类全名，由 {@link java.util.ServiceLoader} 发现
 *
 * 实现要求：
 *  - 实现类本身应无状态（所有连接共享一个 Provider 实例）
 *  - {@link #createHandlers()} 必须返回每连接新实例（除非 Handler 显式标注
 *    {@link io.netty.channel.ChannelHandler.Sharable}）
 *
 * @author yjh_c
 */
public interface ProtocolDecoderProvider {

    /** 协议唯一标识（如 "hj212"、"mqtt-custom"），全局唯一，用于注册和路由 */
    String name();

    /** 协议版本，便于同名协议多版本共存（建议遵循语义化版本） */
    default String version() {
        return "1.0.0";
    }

    /** 协议描述，用于管理界面展示 */
    default String description() {
        return "";
    }

    /**
     * 为一条新连接创建完整的 Handler 链。
     * 框架会在 {@link io.netty.handler.timeout.IdleStateHandler} 之后按数组顺序逐个 addLast。
     *
     * 实现注意：返回的 ChannelHandler 必须是每连接新实例，
     * 除非显式标注 {@code @ChannelHandler.Sharable}。
     */
    ChannelHandler[] createHandlers();

    /**
     * 读写空闲配置（秒）。返回 null 表示用框架默认值（600s）。
     * 不同协议设备的心跳周期不同，应允许 Provider 自定义。
     */
    default IdleConfig idleConfig() {
        return null;
    }

    /** Provider 被卸载前的回调，可清理线程池/缓存等资源 */
    default void destroy() {
    }

    /** 不可变值对象，承载空闲参数 */
    final class IdleConfig {
        public final int readIdleSeconds;
        public final int writeIdleSeconds;
        public final int allIdleSeconds;

        public IdleConfig(int readIdleSeconds, int writeIdleSeconds, int allIdleSeconds) {
            this.readIdleSeconds = readIdleSeconds;
            this.writeIdleSeconds = writeIdleSeconds;
            this.allIdleSeconds = allIdleSeconds;
        }
    }
}
