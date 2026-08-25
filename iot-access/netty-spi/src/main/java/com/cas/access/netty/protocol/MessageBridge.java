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

    /**
     * 手动重投递（重试发送指定日志）。
     * <p>
     * 与 {@link #send} 不同，本方法会：
     * 1. 同步执行发送（或等待异步完成），以便调用方判断是否成功；
     * 2. 不自动写入 bridge_log（由调用方负责删除/保留原日志）。
     *
     * @param serverPort 服务端端口
     * @param serverIp   服务端 IP
     * @param clientPort 客户端端口
     * @param clientIp   客户端 IP
     * @param topicName  目标 Topic（来自日志记录）
     * @param data       原始数据
     * @return true 表示发送成功，false 表示发送失败（已耗尽重试）
     */
    boolean resend(int serverPort, String serverIp, int clientPort, String clientIp,
                   String topicName, String data);
}
