package com.example.netty.echo;

import com.cas.access.netty.protocol.ProtocolDecoderProvider;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;

/**
 * Echo 协议示例。
 *
 * 行为：客户端发什么，服务端原样回显（按行处理，每行加 "Echo: " 前缀）。
 * 用途：调试 netty-server 的协议热插拔流程——reload / bind / unbind / unload。
 *
 * Handler 链路（按 {@link #createHandlers()} 返回顺序装配）：
 *  1. LineBasedFrameDecoder  按行切包
 *  2. StringDecoder          ByteBuf → String
 *  3. EchoHandler            业务回显
 *  4. StringEncoder          String → ByteBuf
 *
 * @author example
 */
public class EchoProtocolProvider implements ProtocolDecoderProvider {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "Echo 调试协议：按行回显客户端发送的数据";
    }

    @Override
    public ChannelHandler[] createHandlers() {
        // 每个连接独立一份（LineBasedFrameDecoder/StringDecoder/EchoHandler 都是有状态或非 @Sharable）
        return new ChannelHandler[]{
                new LineBasedFrameDecoder(65535),
                new StringDecoder(StandardCharsets.UTF_8),
                new EchoHandler()
//                new StringEncoder(StandardCharsets.UTF_8)
        };
    }

    @Override
    public IdleConfig idleConfig() {
        // 60 秒读空闲就断开，方便 debug 时观察超时触发
        return new IdleConfig(60, 0, 0);
    }
}
