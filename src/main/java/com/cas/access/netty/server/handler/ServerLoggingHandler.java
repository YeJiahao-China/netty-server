package com.cas.access.netty.server.handler;

import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * @author JHYe
 * @date 2024/6/21
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class ServerLoggingHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
//        log.info("NioServerSocketChannel成功注册");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
//        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().localAddress();
//        log.info("ServerSocketChannel[ip:{},port:{}] Activated",socketAddress.getHostString(),socketAddress.getPort());
    }
}
