package com.cas.access.netty.protocol;

/**
 * 数据桥接接口：将 TCP 接收到的数据发送到对应的 RocketMQ topic。
 * <p>
 * netty-core 通过此接口调用，实现类在 netty-admin 中，
 * 遵循 SPI 解耦模式（与 ProtocolStore、PortTopicSync 相同）。
 * <p>
 * 该方法应采用异步模式（提交到业务线程池），不阻塞 Netty IO 线程。
 */
public interface MessageBridge {

    /**
     * 将数据桥接发送到指定端口绑定的 topic（异步提交到业务线程池）。
     *
     * @param serverPort 服务端 TCP 监听端口
     * @param serverIp   服务端 IP
     * @param clientPort 客户端远端端口
     * @param clientIp   客户端远端 IP
     * @param data       原始数据内容
     */
    void send(int serverPort, String serverIp, int clientPort, String clientIp, String data);
}
