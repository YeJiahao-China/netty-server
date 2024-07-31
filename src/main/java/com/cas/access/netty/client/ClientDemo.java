package com.cas.access.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.nio.charset.Charset;

/**
 * @author JHYe
 * @date 2023/9/19
 */
@SuppressWarnings({"AlibabaUndefineMagicConstant", "AlibabaRemoveCommentedCode"})
public class ClientDemo {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("...客户端启动...");
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup(10);
        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LineBasedFrameDecoder(65535))
                        // 解码转String，注意调整自己的编码格式GBK、UTF-8
                        .addLast("stringDecoder", new StringDecoder(Charset.forName("UTF-8")))
                        // 解码转String，注意调整自己的编码格式GBK、UTF-8
                        .addLast("stringEncoder", new StringEncoder(Charset.forName("UTF-8")))
                        // 在管道中添加我们自己的接收数据实现方法
                        .addLast("customOutMsgHandler", new CustomOutMsgHandler())//消息出站处理器，在Client发送消息时候会触发此处理器
                        .addLast("customInMsgHandler", new CustomInMsgHandler()); //消息入站处理器
            }
        });
        // 创建客户端连接
        for (int i = 1; i <= 1; i++) {
            ChannelFuture channelFuture = null;
            try {
                channelFuture = bootstrap.connect("192.168.1.8", 10061).sync();
//                channelFuture.channel().closeFuture().sync(); // 等待连接关闭
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                eventLoopGroup.shutdownGracefully(); // 关闭EventLoopGroup
            }
        }
    }
}
