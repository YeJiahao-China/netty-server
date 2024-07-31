package com.cas.access.netty.client;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author JHYe
 * @date 2023/9/19
 */
@SuppressWarnings("AlibabaUndefineMagicConstant")
public class CustomInMsgHandler extends ChannelInboundHandlerAdapter {

    /**
     * 当客户端主动链接服务端的链接后，这个通道就是活跃的了。也就是客户端与服务端建立了通信通道并且可以传输数据
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SocketChannel channel = (SocketChannel) ctx.channel();
        String str = "##0181QN=20240606000056000;ST=22;CN=2061;PW=O447JGPYQ75V3C58D138L2WX;MN=340100001V008;Flag=5;CP=&&DataTime=20240605230000;a34514-Avg=305.000,a34514-Flag=N;a34515-Avg=268.333,a34515-Flag=N&&2340";
        System.out.println(channel.localAddress().getPort() + " 客户端连接成功...");
        ChannelFuture future = ctx.writeAndFlush(str);
        if (future.isDone()){
            System.out.println("客户端数据发送完成");
        }else {
            System.out.println("客户端数据发送异常");
        }
        ctx.close();
    }

    /**
     * 当客户端主动断开服务端的链接后，这个通道就是不活跃的。也就是说客户端与服务端的关闭了通信通道并且不可以传输数据
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("连接关闭" + ctx.channel().localAddress().toString());
        ctx.close();
    }

    /**
     * 读取数据
     *
     * @param ctx
     * @param msg
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        System.out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " client receive：" + msg.toString());
    }

    /**
     * 抓住异常，当发生异常的时候，可以做一些相应的处理，比如打印日志、关闭链接
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("异常信息：\r\n" + cause.getMessage());
        ctx.close();
    }
}
