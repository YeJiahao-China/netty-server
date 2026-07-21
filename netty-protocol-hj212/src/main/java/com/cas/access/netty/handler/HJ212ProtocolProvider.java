package com.cas.access.netty.handler;

import com.cas.access.netty.protocol.ProtocolDecoderProvider;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.string.StringEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * HJ212 协议内置包装 Provider。
 * 把现有 {@link HJ212Decoder} + {@link ReadEventHandler} + {@link StringEncoder} 装配起来。
 *
 * 设计说明：
 *  - {@link HJ212Decoder} 类完全不动，每次 {@link #createHandlers()} 调用都 new 一份
 *    （ByteToMessageDecoder 是有状态的，必须每连接一份）
 *  - {@link ReadEventHandler} 标注了 {@link io.netty.channel.ChannelHandler.Sharable}，
 *    作为 Spring Bean 注入，所有 HJ212 连接共享同一实例
 *
 * @author yjh_c
 */
@Component
public class HJ212ProtocolProvider implements ProtocolDecoderProvider {

    private final ReadEventHandler readEventHandler;

    public HJ212ProtocolProvider(ReadEventHandler readEventHandler) {
        this.readEventHandler = readEventHandler;
    }

    @Override
    public String name() {
        return "hj212";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "HJ212 环保传输协议（内置）";
    }

    @Override
    public ChannelHandler[] createHandlers() {
        return new ChannelHandler[]{
                new HJ212Decoder(),
                readEventHandler,
                new StringEncoder(StandardCharsets.UTF_8)
        };
    }

    @Override
    public IdleConfig idleConfig() {
        return new IdleConfig(600, 600, 600);
    }
}
