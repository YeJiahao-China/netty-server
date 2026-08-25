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
    public static void main(String[] args) {
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i <= 10; i++) {
                    ChannelFuture channelFuture = null;
                    try {
                        //  111.229.122.17
                        channelFuture = bootstrap.connect("111.229.122.17", 10101).sync();
//                channelFuture = bootstrap.connect("127.0.0.1", 10101).sync();
//                channelFuture = bootstrap.connect("127.0.0.1", 10101).sync();
                        channelFuture.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future)  {
                                System.out.println("IO操作完成...");
                            }
                        });
                        channelFuture.get();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i <= 10; i++) {
                    ChannelFuture channelFuture = null;
                    try {
                        //  111.229.122.17
                        channelFuture = bootstrap.connect("111.229.122.17", 10101).sync();
//                channelFuture = bootstrap.connect("127.0.0.1", 10101).sync();
//                channelFuture = bootstrap.connect("127.0.0.1", 10101).sync();
                        channelFuture.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future)  {
                                System.out.println("IO操作完成...");
                            }
                        });
                        channelFuture.get();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
}
