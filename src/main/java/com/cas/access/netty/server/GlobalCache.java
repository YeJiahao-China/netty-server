package com.cas.access.netty.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author JHYe
 * @date 2023/10/26
 */
@SuppressWarnings("AlibabaCommentsMustBeJavadocFormat")
public class GlobalCache {

    // 监听端口与NioServerSocketChannel
    public static Map<Integer, Channel> SP_ServerChannel_Map = new ConcurrentHashMap<>();

    // 监听端口与SocketChannel集合
    public static Map<Integer, Set<Channel>> SP_SocketChannel_Map = new ConcurrentHashMap<>();

    // 管理一个全局map，保存连接进服务端的设备状态
    public static final ConcurrentHashMap<ChannelId, Channel> CONNECTION_STATUS_MAP = new ConcurrentHashMap<>();
}
