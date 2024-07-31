package com.cas.access.netty.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author JHYe
 * @date 2023/10/26
 */
public class ServerConnectionManager {

    // 服务监听端口与NioServerSocketChannel
    public static Map<Integer, Channel> portServerChannelMap = new ConcurrentHashMap<>();

    // 服务监听端口与SocketChannel集合
    public static Map<Integer, Set<NioSocketChannel>> portSocketChannelMap = new ConcurrentHashMap<>();

    // 管理一个全局map，保存连接进服务端的设备状态
    public static final ConcurrentHashMap<ChannelId, NioSocketChannel> totalConnectionMap = new ConcurrentHashMap<>();
}
