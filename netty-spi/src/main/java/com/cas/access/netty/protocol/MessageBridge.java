package com.cas.access.netty.protocol;

/**
 * 数据桥接接口：将 TCP 接收到的数据发送到对应的 RocketMQ topic。
 * <p>
 * netty-core 通过此接口调用，实现类在 netty-admin 中，
 * 遵循 SPI 解耦模式（与 ProtocolStore、PortTopicSync 相同）。
 */
public interface MessageBridge {

    /**
     * 将数据桥接发送到指定端口绑定的 topic。
     *
     * @param port TCP 监听端口
     * @param data 原始数据内容
     * @return true=发送成功, false=发送失败或无绑定
     */
    boolean send(int port, String data);
}
