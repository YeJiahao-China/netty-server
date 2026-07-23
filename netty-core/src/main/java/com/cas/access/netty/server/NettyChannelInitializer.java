package com.cas.access.netty.server;

import com.cas.access.netty.handler.ConnectEventHandler;
import com.cas.access.netty.handler.ReadEventHandler;
import com.cas.access.netty.protocol.LoadedProtocol;
import com.cas.access.netty.protocol.ProtocolDecoderProvider;
import com.cas.access.netty.protocol.ProtocolRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


/**
 * NettyServer 连接通道初始化类。
 *
 * 改造说明：
 *  原版本硬编码 {@code new HJ212Decoder()}，新增协议必须改代码 + 重启。
 *  现版本改为：根据服务端口查询 {@link ProtocolRegistry} 中登记的协议 →
 *  调用 {@link ProtocolDecoderProvider#createHandlers()} 动态装配 Pipeline。
 *
 * 新增协议的方式：
 *  - 内置：实现 ProtocolDecoderProvider 并标注 @Component
 *  - 外部：打成独立 jar 放入 protocols/ 目录，通过 HTTP 接口 reload
 *
 * @author yjh_c
 */
@ChannelHandler.Sharable
@Component
@Slf4j
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {

    /** 默认空闲超时（秒），Provider 未提供 idleConfig 时使用 */
    private static final int DEFAULT_IDLE_SECONDS = 6000;

    @Resource
    private ProtocolRegistry protocolRegistry;

    @Resource
    private ConnectEventHandler connectEventHandler;

    @Resource
    private ReadEventHandler readEventHandle;

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        int serverPort = socketChannel.localAddress().getPort();
        String clientIp = socketChannel.remoteAddress().getAddress().getHostAddress();
        int clientPort = socketChannel.remoteAddress().getPort();

        try {
            String protoName = protocolRegistry.getProtocolNameByPort(serverPort);
            LoadedProtocol loaded = protoName == null ? null : protocolRegistry.getProvider(protoName);

            if (loaded == null || !loaded.isActive()) {
                log.error("[拒绝连接] 端口 {} 未绑定可用协议，client={}:{}", serverPort, clientIp, clientPort);
                socketChannel.close();
                return;
            }

            ProtocolDecoderProvider provider = loaded.getProvider();
            ProtocolDecoderProvider.IdleConfig idle = provider.idleConfig();
            int readIdle  = idle == null ? DEFAULT_IDLE_SECONDS : idle.readIdleSeconds;
            int writeIdle = idle == null ? DEFAULT_IDLE_SECONDS : idle.writeIdleSeconds;
            int allIdle   = idle == null ? DEFAULT_IDLE_SECONDS : idle.allIdleSeconds;
            pipeline.addLast("idleStateHandler", new IdleStateHandler(readIdle, writeIdle, allIdle));
            pipeline.addLast("connectEventHandler", connectEventHandler);
            ChannelHandler[] handlers = provider.createHandlers();
            if (handlers != null && handlers.length > 0) {
                for (int i = 0; i < handlers.length; i++) {
                    pipeline.addLast(provider.name() + "_" + i, handlers[i]);
                }
            }
            pipeline.addLast("readEventHandle", readEventHandle);
            log.info("[Pipeline装配成功] client={}:{} port={} protocol={}",
                    clientIp, clientPort, serverPort, protoName);
        } catch (Exception e) {
            log.error("Init ChannelPipeline Error - client={}:{} port={} - {}",
                    clientIp, clientPort, serverPort, e.getMessage(), e);
            socketChannel.close();
        }
    }
}
