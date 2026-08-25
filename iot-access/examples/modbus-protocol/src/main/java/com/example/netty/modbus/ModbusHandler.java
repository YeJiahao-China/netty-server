package com.example.netty.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modbus 业务 Handler。
 *
 * <p>职责：
 *  1. 打印上游 ModbusDecoder 解析后的报文字段
 *  2. 回复一个固定的 Modbus 异常响应（不需要根据请求动态构造）
 *
 * <p>固定 ACK 内容：
 * <pre>
 * TxId=0xFFFF (示例占位), ProtoId=0, Length=3, UnitId=0xFF,
 * FC=0x81 (Read Coils 异常响应示例), Exception Code=0x00
 * </pre>
 *
 * <p>总字节数 = 6 (MBAP) + 3 (UnitId + FC + ExceptionCode) = 9 bytes
 *
 * @author example
 */
public class ModbusHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ModbusHandler.class);

    /** 固定 ACK 帧（9 字节） */
    private static final byte[] FIXED_ACK = new byte[]{
            (byte) 0xFF, (byte) 0xFF,   // Transaction ID
            0x00, 0x00,                  // Protocol ID (Modbus)
            0x00, 0x03,                  // Length = 3
            (byte) 0xFF,                 // Unit ID
            (byte) 0x81,                 // Function Code | 0x80 (异常响应)
            0x00                         // Exception Code
    };

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("[Modbus] 客户端连接: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        String parsed = (String) msg;
        log.info("[Modbus] 收到报文: {}", parsed);

        // 直接写 ByteBuf 出站，不需要 Encoder
        ByteBuf ack = Unpooled.wrappedBuffer(FIXED_ACK);
        log.info("[Modbus] 回复固定 ACK: 9 字节");
        ctx.writeAndFlush(ack);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("[Modbus] 客户端断开: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[Modbus] 异常: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        log.warn("[Modbus] 收到事件: {}", evt);
        super.userEventTriggered(ctx, evt);
    }
}
