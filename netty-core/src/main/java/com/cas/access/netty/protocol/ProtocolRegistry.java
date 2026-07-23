package com.cas.access.netty.protocol;

import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.util.NettyServerUtil;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议注册中心：单例 Bean。
 *
 * 维护两份索引：
 *  - {@link #protocols}：name → LoadedProtocol
 *  - {@link #portBindings}：port → protocolName
 *
 * 并发策略：
 *  - 读操作完全无锁（{@link ConcurrentHashMap}），适配 Netty 高并发 initChannel
 *  - 写操作（register/unregister/bindPort）整体 synchronized，因属低频运维操作
 *
 * 卸载策略（重点）：
 *  - {@link #unregister(String)}（外部调用，彻底删除协议）：
 *      1. 关闭该协议绑定的所有端口监听 + 踢掉所有活跃连接
 *      2. 清理 portBindings 中相关映射
 *      3. 调 Provider.destroy() 清理资源
 *      4. close URLClassLoader 释放 jar 文件句柄
 *    这样保证卸载后没有 Handler 实例引用 Provider 的类，避免 ClassLoader 泄漏
 *  - {@link #unregisterInternal(String, boolean)}（内部调用）：
 *      hotReplace=true（register 热替换）：关闭旧端口监听 + 旧连接 + destroy + close CL，不调 syncUnload
 *      hotReplace=false（unregister 卸载）：仅 destroy + close CL（端口已在 unregister 中关闭），调 syncUnload
 *      关闭旧连接是为了让 Handler 被 GC → ClassLoader 可回收，避免 Metaspace 泄漏
 *
 * @author yjh_c
 */
@Slf4j
@Component
public class ProtocolRegistry {

    private final Map<String, LoadedProtocol> protocols = new ConcurrentHashMap<>();
    private final Map<Integer, String> portBindings = new ConcurrentHashMap<>();

    /**
     * 数据库镜像回调。
     * 由 netty-admin 实现（ProtocolJarRegistryService implements ProtocolDbSync）。
     * 通过 SPI 接口反转依赖，避免 core 反向依赖 admin。
     * required=false：admin 不在 classpath 或 DB 不可用时退化到纯内存模式。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProtocolDbSync dbSync;

    /**
     * 端口绑定持久化回调。
     * 由 netty-admin 的 PortBindingService 实现。
     * required=false：DB 不可用时退化为纯内存模式。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PortBindingStore bindingStore;

    /**
     * 启动时打印 DB 同步组件的注入状态，方便排查「协议没写库」类问题。
     * required=false 意味着 admin 模块不在 classpath 时为 null（纯内存模式），
     * 但用户场景下 admin 应该在 classpath，理论上都应该成功注入。
     */
    @PostConstruct
    public void logInjectionStatus() {
        log.info("ProtocolRegistry 注入状态: dbSync={}, bindingStore={}",
                dbSync == null ? "NULL(纯内存模式)" : dbSync.getClass().getName(),
                bindingStore == null ? "NULL(纯内存模式)" : bindingStore.getClass().getName());
    }

    /* ========== 协议管理 ========== */

    public LoadedProtocol getProvider(String name) {
        LoadedProtocol lp = protocols.get(name);
        return (lp != null && lp.isActive()) ? lp : null;
    }

    public Collection<LoadedProtocol> listAll() {
        return protocols.values();
    }

    /**
     * 注册协议。若同名协议已存在，先热替换旧的（关旧端口监听+旧连接+destroy+close CL），再注册新的。
     * 端口绑定关系保持不变，由调用方在 register 完成后重新 bindPort 恢复监听。
     * 热替换时不调 syncUnload（紧接着 syncRegister 会更新 DB），避免错误清理 port_binding 表。
     */
    public synchronized void register(LoadedProtocol lp) {
        String key = lp.getName();
        LoadedProtocol old = protocols.get(key);
        if (old != null) {
            log.warn("协议[{}]被覆盖", key);
            unregisterInternal(key, true);
        }
        protocols.put(key, lp);
        log.info("协议注册成功: name={}, version={}, source={}, classLoader={}", key, lp.getVersion(), lp.getSource(),lp.getClassLoader());
        if (dbSync != null) dbSync.syncRegister(lp);
    }

    public synchronized boolean unregister(String name) {
        List<Integer> boundPorts = getBoundPorts(name);
        boolean closeSuccess = true;

        if (!boundPorts.isEmpty()) {
            log.info("协议[{}]绑定的端口 {} 将被关闭", name, boundPorts);
            for (int port : boundPorts) {
                // 💥 传入超时时间，5秒足够绝大多数正常连接完成关闭
                if (!NettyServerUtil.closeListen(port, 5)) {
                    closeSuccess = false;
                    log.warn("端口[{}]连接未完全关闭，但仍继续卸载协议[{}]", port, name);
                }
            }
            boundPorts.forEach(portBindings::remove);
        }
        // 即使部分连接超时，也继续执行卸载（超时连接会在后续 GC 中被处理）
        // 但记录告警以便排查
        if (!closeSuccess) {
            log.warn("协议[{}]卸载时存在连接关闭超时，建议观察Metaspace是否泄漏", name);
        }

        return unregisterInternal(name, false);
    }


    /**
     * 卸载协议（彻底删除）。
     *
     * 执行步骤：
     *  1. 取出该协议绑定的所有端口
     *  2. 对每个端口调用 {@link NettyServerUtil#closeListen(int)}：
     *     - 关闭端口监听
     *     - 强制关闭该端口下所有客户端连接（保证 Pipeline 销毁，Handler 实例可被 GC）
     *  3. 清理 portBindings 中该协议的映射
     *  4. 调 Provider.destroy() 让实现方清理自身资源
     *  5. close URLClassLoader 释放 jar 文件句柄（Windows 上解锁 jar 文件）
     *
     * @return true=卸载成功；false=协议未注册
     */
//    public synchronized boolean unregister(String name) {
//        // 1. 关闭该协议绑定的所有端口监听 + 踢掉所有活跃连接
//        List<Integer> boundPorts = getBoundPorts(name);
//        if (!boundPorts.isEmpty()) {
//            log.info("协议[{}]绑定的端口 {} 将被关闭，所有活跃连接会被强制断开", name, boundPorts);
//            for (int port : boundPorts) {
//                try {
//                    NettyServerUtil.closeListen(port);
//                } catch (Exception e) {
//                    log.error("关闭端口[{}]监听失败: {}", port, e.getMessage(), e);
//                }
//            }
//            // 2. 清理 portBindings
//            boundPorts.forEach(portBindings::remove);
//        }
//        // 3. 卸载 Provider + ClassLoader
//        return unregisterInternal(name);
//    }

    /**
     * 内部卸载：关闭旧端口监听 + 旧连接 + destroy Provider + close ClassLoader。
     *
     * @param name       协议名
     * @param hotReplace true=热替换（register 覆盖同名协议），不调 syncUnload（syncRegister 会更新 DB）；
     *                   false=彻底卸载（unregister 调用），调 syncUnload 标记 DB active=false
     */
    private boolean unregisterInternal(String name, boolean hotReplace) {
        LoadedProtocol lp = protocols.remove(name);
        if (lp == null) {
            return false;
        }
        // 热替换路径：立即关闭旧连接（卸载路径此时 portBindings 已空，无操作）
        // 旧连接的 Handler 持有 Provider 的 Class 引用，不关闭会导致 ClassLoader 无法 GC
        if (lp.getClassLoader() != null) {
            closeOldChannels(name);
        }
        try {
            lp.getProvider().destroy();
        } catch (Throwable t) {
            log.warn("Provider.destroy() 异常: {}", t.getMessage());
        }
        if (lp.getClassLoader() != null) {
            try {
                lp.getClassLoader().close();
                log.info("协议[{}]已卸载, classLoader[{}]已关闭", name,lp.getClassLoader());
            } catch (Exception e) {
                log.warn("ClassLoader 关闭失败: {}", e.getMessage());
            }
        }
        // 热替换不调 syncUnload（syncRegister 会更新 DB）；卸载才调 syncUnload
        if (!hotReplace && dbSync != null) {
            dbSync.syncUnload(name);
        }
        return true;
    }

    /**
     * 关闭旧协议绑定的端口监听（ServerSocketChannel）及所有活跃客户端连接（SocketChannel）。
     *
     * <p>用于热替换场景：
     * <ul>
     *   <li>关闭 ServerSocketChannel（停止 accept 新连接），防止客户端即时重连
     *       在新 Provider 就绪前接入。调用方负责在 register 完成后重新
     *       {@link com.cas.access.netty.util.NettyServerUtil#bindPort} 恢复监听。</li>
     *   <li>关闭旧客户端连接：旧连接的 Handler 持有旧 Provider 的 Class 引用，
     *       不关闭会导致 ClassLoader 无法被 GC。</li>
     * </ul>
     * <p>不清理 portBindings、不 remove SocketChannelSet（避免 channelInactive 回调 NPE）。
     * 卸载路径下 {@link #getBoundPorts} 返回空（portBindings 已被 {@link #unregister} 清理），无操作。
     */
    public void closeOldChannels(String protocolName) {
        List<Integer> boundPorts = getBoundPorts(protocolName);
        for (int port : boundPorts) {
            // 1. 关闭 ServerSocketChannel（停止 accept 新连接），sync 等待关闭完成
            Channel serverChannel = GlobalCache.ServerPort_ServerSocketChannel_Map.remove(port);
            if (serverChannel != null) {
                try {
                    serverChannel.close().sync();
                    log.info("协议[{}]热替换，关闭端口[{}]监听", protocolName, port);
                } catch (Exception e) {
                    log.warn("关闭端口[{}]监听异常: {}", port, e.getMessage());
                }
            }
            // 2. 关闭该端口下所有活跃客户端连接（不 remove Set，避免 channelInactive 回调 NPE）
            Set<Channel> channels = GlobalCache.ServerPost_SocketChannelSet_Map.get(port);
            if (channels == null) {
                continue;
            }
            int closed = 0;
            for (Channel ch : new ArrayList<>(channels)) {
                if (ch.isActive()) {
                    ch.close();
                    closed++;
                }
            }
            if (closed > 0) {
                log.info("协议[{}]热替换，关闭端口[{}]下 {} 个旧连接", protocolName, port, closed);
            }
        }
    }

    /**
     * 热升级前置：关闭旧端口监听 + 旧连接 + 旧 ClassLoader。
     *
     * <p>用于「上传新版本 jar 覆盖旧版本」场景，执行顺序：
     * <ol>
     *   <li>关闭 ServerSocketChannel（停止 accept 新连接），防止热替换期间新连接接入</li>
     *   <li>关闭旧客户端连接：旧连接的 Handler 持有旧 Provider 的 Class 引用，
     *       不关闭会导致 ClassLoader 无法被 GC</li>
     *   <li>关闭旧 ClassLoader 释放 jar 文件锁（JDK 9+ 下 close 后文件句柄即释放）</li>
     * </ol>
     * <p>不移除 {@code protocols} 条目，不动 portBindings（register 后用于重新 bind）。
     * 紧接着应调 {@link #register(LoadedProtocol)} 覆盖条目，
     * register 内部的 {@link #closeOldChannels} 因端口已关闭而 no-op。
     */
    public synchronized void closeClassLoaderForUpgrade(String name) {
        // 再关闭旧 ClassLoader（释放 jar 文件锁）
        LoadedProtocol lp = protocols.get(name);
        if (lp != null && lp.getClassLoader() != null) {
            try {
                lp.getClassLoader().close();
                log.info("协议[{}]的旧 ClassLoader 已关闭（热升级前置，释放 jar 文件锁）", name);
            } catch (Exception e) {
                log.warn("关闭协议[{}]的旧 ClassLoader 失败: {}", name, e.getMessage());
            }
        }
    }

    /* ========== 端口绑定 ========== */

    public void bindPortToProtocol(int port, String protocolName) {
        if (!protocols.containsKey(protocolName)) {
            throw new IllegalArgumentException("协议[" + protocolName + "]未注册");
        }
        portBindings.put(port, protocolName);
        if (bindingStore != null) bindingStore.persistBind(port, protocolName);
    }

    /**
     * 仅内存绑定（启动加载场景：数据已在 DB，避免无意义的 UPDATE）。
     * 由 {@link ProtocolBootstrap} 启动时从 DB 装载初始绑定时调用。
     */
    public void bindPortToProtocolInternal(int port, String protocolName) {
        portBindings.put(port, protocolName);
    }

    public String unbindPort(int port) {
        String removed = portBindings.remove(port);
        if (bindingStore != null) bindingStore.persistUnbind(port);
        return removed;
    }

    public String getProtocolNameByPort(int port) {
        return portBindings.get(port);
    }

    /** 返回端口→协议的快照（按端口升序，便于展示） */
    public Map<Integer, String> getAllBindings() {
        return new TreeMap<>(portBindings);
    }

    /** 返回该协议当前绑定的所有端口（按升序） */
    public List<Integer> getBoundPorts(String protocolName) {
        List<Integer> ports = new ArrayList<>();
        portBindings.forEach((p, n) -> {
            if (n.equals(protocolName)) {
                ports.add(p);
            }
        });
        return ports;
    }
}
