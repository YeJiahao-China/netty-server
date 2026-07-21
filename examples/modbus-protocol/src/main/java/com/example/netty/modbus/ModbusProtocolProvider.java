package com.example.netty.modbus;

import com.cas.access.netty.protocol.ProtocolDecoderProvider;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Modbus TCP 协议示例 Provider。
 *
 * <p>Handler 链路（按 {@link #createHandlers()} 返回顺序装配）：
 * <ol>
 *   <li>{@link LengthFieldBasedFrameDecoder} —— 按 MBAP header 的 Length 字段拆包</li>
 *   <li>{@link ModbusDecoder}                —— ByteBuf → 可读字符串</li>
 *   <li>{@link ModbusHandler}                —— 打印 + 回固定 ACK</li>
 * </ol>
 *
 * <p>LengthFieldBasedFrameDecoder 参数说明（Modbus TCP 标准参数）：
 * <ul>
 *   <li>maxFrameLength = 260          —— Modbus TCP 最大帧 260 字节</li>
 *   <li>lengthFieldOffset = 4         —— Length 字段从第 5 字节开始（跳过 TxId+ProtoId）</li>
 *   <li>lengthFieldLength = 2         —— Length 字段本身 2 字节</li>
 *   <li>lengthAdjustment = 0          —— Length 字段值即"后续字节数"，无需调整</li>
 *   <li>initialBytesToStrip = 0       —— 不剥离，整包传给下游</li>
 * </ul>
 *
 * @author example
 */
public class ModbusProtocolProvider implements ProtocolDecoderProvider {

    /** Modbus TCP 单帧最大长度：MBAP(7) + PDU(max 253) */
    private static final int MAX_FRAME_LENGTH = 260;

    /** Length 字段在帧中的偏移（TxId 2 + ProtoId 2 = 4） */
    private static final int LENGTH_FIELD_OFFSET = 4;

    /** Length 字段长度（大端 unsigned short = 2 字节） */
    private static final int LENGTH_FIELD_LENGTH = 2;

    @Override
    public String name() {
        return "modbus-tcp";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "Modbus TCP 协议示例（仅打印报文 + 固定 ACK）";
    }

    @Override
    public ChannelHandler[] createHandlers() {
        // 每个连接独立一份（拆包器、解码器、业务 Handler 都有状态）
        return new ChannelHandler[]{
                new LengthFieldBasedFrameDecoder(
                        MAX_FRAME_LENGTH,
                        LENGTH_FIELD_OFFSET,
                        LENGTH_FIELD_LENGTH,
                        0,
                        0
                ),
                new ModbusDecoder(),
                new ModbusHandler()
        };
    }

    @Override
    public IdleConfig idleConfig() {
        // Modbus 设备通常 60-120 秒轮询一次，给 120 秒读超时
        return new IdleConfig(120, 0, 0);
    }
}
