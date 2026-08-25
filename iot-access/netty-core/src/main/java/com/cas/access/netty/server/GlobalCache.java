package com.cas.access.netty.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局缓存：管理端口监听通道、端口下客户端连接集合、全局连接状态。
 *
 * <p>所有字段均为 private，外部统一通过封装方法访问，保证并发安全：
 * <ul>
 *   <li>三个 Map 均为 {@link ConcurrentHashMap}，单次操作原子。</li>
 *   <li>端口连接集合使用 {@code ConcurrentHashMap.newKeySet()} 创建线程安全 Set。</li>
 *   <li>复合操作（如 get+add）通过 {@code computeIfAbsent} 等原子方法保证一致性。</li>
 * </ul>
 *
 * @author JHYe
 * @date 2023/10/26
 */
@SuppressWarnings("AlibabaCommentsMustBeJavadocFormat")
public class GlobalCache {

    /** 端口 → ServerSocketChannel（监听通道） */
    public static final ConcurrentHashMap<Integer, Channel> PORT_SERVER_CHANNEL_MAP = new ConcurrentHashMap<>();

    /** 端口 → SocketChannel 集合（连到该监听端口的客户端通道） */
    public static final ConcurrentHashMap<Integer, Set<Channel>> PORT_SOCKET_CHANNELS_MAP = new ConcurrentHashMap<>();

    /** ChannelId → Channel（全局连接状态） */
    public static final ConcurrentHashMap<ChannelId, Channel> CONNECTION_MAP = new ConcurrentHashMap<>();

    private GlobalCache() {
    }

    // ==================== 端口 → ServerSocketChannel ====================

    /** 绑定端口与 ServerSocketChannel（启动/新增协议绑定端口时调用） */
    public static void bindServerChannel(int port, Channel channel) {
        PORT_SERVER_CHANNEL_MAP.put(port, channel);
    }

    /** 移除并返回端口对应的 ServerSocketChannel（卸载/热替换关闭监听时调用） */
    public static Channel removeServerChannel(int port) {
        return PORT_SERVER_CHANNEL_MAP.remove(port);
    }

    /** 获取端口对应的 ServerSocketChannel */
    public static Channel getServerChannel(int port) {
        return PORT_SERVER_CHANNEL_MAP.get(port);
    }

    /** 端口是否正在监听 */
    public static boolean isListening(int port) {
        return PORT_SERVER_CHANNEL_MAP.containsKey(port);
    }

    // ==================== 端口 → SocketChannel 集合 ====================

    /** 注册端口的连接集合（端口开始监听时调用，初始化一个线程安全 Set） */
    public static void registerPort(int port) {
        PORT_SOCKET_CHANNELS_MAP.computeIfAbsent(port, k -> ConcurrentHashMap.newKeySet());
    }

    /** 移除端口的连接集合并返回（端口关闭时调用，返回的 Set 可供调用方遍历关闭连接） */
    public static Set<Channel> unregisterPort(int port) {
        return PORT_SOCKET_CHANNELS_MAP.remove(port);
    }

    /** 添加连接到端口集合（channelActive 时调用，内部 computeIfAbsent 保证 Set 存在） */
    public static void addSocketChannel(int port, Channel channel) {
        PORT_SOCKET_CHANNELS_MAP.computeIfAbsent(port, k -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    /** 从端口集合移除连接（channelInactive/超时时调用，null 安全） */
    public static void removeSocketChannel(int port, Channel channel) {
        Set<Channel> set = PORT_SOCKET_CHANNELS_MAP.get(port);
        if (set != null) {
            set.remove(channel);
        }
    }

    /** 获取端口下的连接集合引用（遍历需调用方自行拷贝防御） */
    public static Set<Channel> getSocketChannels(int port) {
        return PORT_SOCKET_CHANNELS_MAP.get(port);
    }

    /** 获取端口下的连接数（null 安全） */
    public static int getConnectionCount(int port) {
        Set<Channel> set = PORT_SOCKET_CHANNELS_MAP.get(port);
        return set == null ? 0 : set.size();
    }

    // ==================== ChannelId → Channel（全局连接状态） ====================

    /** 注册连接（channelActive 时调用） */
    public static void addConnection(ChannelId channelId, Channel channel) {
        CONNECTION_MAP.put(channelId, channel);
    }

    /** 移除连接并返回旧值（channelInactive/异常时调用） */
    public static Channel removeConnection(ChannelId channelId) {
        return CONNECTION_MAP.remove(channelId);
    }

    /** 连接是否存在 */
    public static boolean hasConnection(ChannelId channelId) {
        return CONNECTION_MAP.containsKey(channelId);
    }

    /** 获取全局总连接数 */
    public static int getTotalConnectionCount() {
        return CONNECTION_MAP.size();
    }
}
