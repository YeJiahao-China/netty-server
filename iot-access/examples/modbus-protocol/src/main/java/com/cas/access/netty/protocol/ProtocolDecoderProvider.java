package com.cas.access.netty.protocol;

import io.netty.channel.ChannelHandler;

/**
 * 协议接入提供者 SPI 接口。
 *
 * <p><b>注意：本文件是从 netty-server 主项目同步过来的接口副本</b>，
 * 仅为方便本 modbus 示例独立编译（因为主项目是 Spring Boot fat jar，
 * 无法直接作为 Maven 依赖被 import）。
 *
 * <p>运行时（jar 被加载到 netty-server 中）：
 *  - modbus 协议 jar 的 URLClassLoader.parent 是主项目的 ClassLoader
 *  - JVM 按 parent-first 策略加载本接口，最终解析为主项目中的同一份接口
 *  - 因此 ServiceLoader 能正确识别 ModbusProtocolProvider 实现
 *
 * @author yjh_c
 */
public interface ProtocolDecoderProvider {

    String name();

    default String version() {
        return "1.0.0";
    }

    default String description() {
        return "";
    }

    ChannelHandler[] createHandlers();

    default IdleConfig idleConfig() {
        return null;
    }

    default void destroy() {
    }

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
