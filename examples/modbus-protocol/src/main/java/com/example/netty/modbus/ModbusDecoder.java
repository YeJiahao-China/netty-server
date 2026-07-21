package com.example.netty.modbus;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Modbus TCP 解码器：把一帧 ByteBuf 解析为可读字符串。
 *
 * <p>前置条件：上游必须是 {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder}，
 * 它已经按 MBAP header 中的 Length 字段把粘包/拆包处理好了。
 *
 * <p>Modbus TCP 帧结构（MBAP header + PDU）：
 * <pre>
 * +-----------------+-----------------+-----------------+-------------+--------------+----------+
 * | Transaction ID  | Protocol ID     | Length          | Unit ID     | Function Code| Data     |
 * | 2 bytes (BE)    | 2 bytes (BE)    | 2 bytes (BE)    | 1 byte      | 1 byte       | N bytes  |
 * +-----------------+-----------------+-----------------+-------------+--------------+----------+
 *                  MBAP Header (7 bytes)               |                  PDU (Length - 1)
 * </pre>
 *
 * <p>Length 字段的值 = Unit ID(1) + PDU 字节数。
 *
 * @author example
 */
public class ModbusDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // LengthFieldBasedFrameDecoder 已经保证了一帧是完整的，可以直接按顺序读
        int transactionId = in.readUnsignedShort();
        int protocolId   = in.readUnsignedShort();
        int length       = in.readUnsignedShort();
        int unitId       = in.readUnsignedByte();
        int functionCode = in.readUnsignedByte();

        // 剩余字节作为 PDU 的 Data 部分
        int dataLen = in.readableBytes();
        byte[] data = new byte[dataLen];
        in.readBytes(data);

        String parsed = String.format(
                "TxId=%d, ProtoId=%d, Length=%d, UnitId=%d, FC=%d, Data=%s",
                transactionId, protocolId, length, unitId, functionCode, bytesToHex(data)
        );
        out.add(parsed);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes.length == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
