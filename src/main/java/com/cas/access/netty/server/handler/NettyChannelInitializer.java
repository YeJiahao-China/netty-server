package com.cas.access.netty.server.handler;

import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;


/**
 * NettyServer连接通道初始化类，用于数据处理线程初始化
 */
@ChannelHandler.Sharable
@Component
@Slf4j
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {


    @Resource
    private ReadEventHandler readEventHandler;

    @Resource
    private ConnectEventHandler connectEventHandler;


    @Override
    protected void initChannel(SocketChannel socketChannel) {
        //增加任务处理管道 pipeline
        ChannelPipeline channelPipeline = socketChannel.pipeline();
        InetAddress clientAddress = socketChannel.remoteAddress().getAddress();
        try {
            InetSocketAddress localSocketAddress = socketChannel.localAddress();
            int serverPort = localSocketAddress.getPort();
            // 连接事件handler
//            channelPipeline.addLast("connectEventHandler", connectEventHandler);
            //心跳检测，读超时，写超时，读写超时
//            channelPipeline.addLast("idleStateHandler", new IdleStateHandler(1800, 1800, 1800));
            //获取客户端真实ip
//            channelPipeline.addLast("proxyIpDecoder", new ProxyIpDecoder());
            //解码器
//            String delimiterStr = DelimiterEnum.delimiter(serverPort);
//            if (delimiterStr == null){
//                channelPipeline.addLast("lineBasedFrameDecoder", new LineBasedFrameDecoder(65535));
//            }else {
//                ByteBuf delimiter = Unpooled.copiedBuffer(delimiterStr.getBytes());
//                DelimiterBasedFrameDecoder delimiterBasedFrameDecoder = new DelimiterBasedFrameDecoder(65535, delimiter);
//                channelPipeline.addLast("delimiterBasedFrameDecoder", delimiterBasedFrameDecoder);
//            }
            //stringDecoder解码器
//            channelPipeline.addLast("stringDecoder", new StringDecoder(StandardCharsets.UTF_8));

         //   channelPipeline.addLast("proxyIpDecoder", new ProxyIpDecoder());
            // HJ212协议解码器
            channelPipeline.addLast("idleStateHandler", new IdleStateHandler(10,10,10, TimeUnit.HOURS));
            channelPipeline.addLast("connectEventHandler",connectEventHandler);
            // 业务解码器
//            channelPipeline.addLast("hj212Decoder", new HJ212Decoder());
            channelPipeline.addLast("stringDecoder", new StringDecoder(Charset.defaultCharset()));

            //stringEncoder编码器
            channelPipeline.addLast("stringEncoder", new StringEncoder(StandardCharsets.UTF_8));
            //读数据handler
            channelPipeline.addLast("readerHandler", readEventHandler);
        } catch (Exception e) {
            socketChannel.close();
            log.error("初始化ChannelPipeline异常 - [客户端IP:{}-Port:{}] - [异常信息:{}]", clientAddress.getHostAddress(), socketChannel.remoteAddress().getPort(), e.getMessage());
        }
    }
}
