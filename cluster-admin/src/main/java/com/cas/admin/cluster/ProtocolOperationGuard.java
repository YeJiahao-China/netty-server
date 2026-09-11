package com.cas.admin.cluster;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 协议管理操作进程内互斥守卫。
 *
 * <p>背景：cluster-admin 的协议写操作（upload/update/bind/unbind/unload/purge/reload）
 * 临界区包含「广播所有节点 + 收口写 DB」，最长可达广播读超时（默认 60s）。
 * 若同一协议的两个管理操作并发执行（如 unload 与 update 撞车），会产生：
 * <ul>
 *   <li>DB 状态后写者胜：unload 的兜底 UNLOADED 可能覆盖 update 刚写入的 REGISTERED，
 *       造成「节点运行着新版本、DB 却显示已卸载」的无声漂移，节点重启后协议消失；</li>
 *   <li>广播指令交错：两个操作的分发请求以不确定顺序到达各节点。</li>
 * </ul>
 *
 * <p>使用方式：接口入口 {@link #tryLock(String)} 非阻塞竞争（失败直接返回
 * 「操作进行中」提示），业务主体（含广播与 DB 收口）放入 try 块，finally 中 {@link #unlock(String)}。
 *
 * <p>实现说明：
 * <ul>
 *   <li>按协议名分锁，不同协议的操作互不阻塞；锁对象常驻（协议数量有限，无内存压力）；</li>
 *   <li>节点侧的 syncRegister/syncUnload 发生在广播 HTTP 响应返回之前，
 *       因此锁住「广播+收口」即可同时覆盖节点侧 DB 写入，无需节点侧配合；</li>
 *   <li><b>仅适用于 cluster-admin 单实例部署</b>。若未来多实例部署，
 *       需将本组件替换为跨实例实现（如 PG advisory lock / Redis 锁），
 *       方法签名保持不变即可平滑替换调用方。</li>
 * </ul>
 */
@Component
public class ProtocolOperationGuard {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 非阻塞竞争指定协议的操作锁。
     *
     * @param key 锁标识（协议名；无协议维度的端口类操作用 "port:端口号"）
     * @return true=竞争成功（必须在 finally 中调用 {@link #unlock}）；false=已有操作进行中
     */
    public boolean tryLock(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock()).tryLock();
    }

    /**
     * 释放操作锁。
     * 仅释放当前线程持有的锁，未持有时静默跳过，避免 IllegalMonitorStateException。
     */
    public void unlock(String key) {
        ReentrantLock lock = locks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
