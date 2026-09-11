package com.cas.admin.schedule;

import com.cas.admin.cluster.ProtocolReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 协议对账定时调度。
 *
 * <p>薄壳：仅负责定时触发与异常兜底，对账逻辑全部在
 * {@link ProtocolReconcileService}（保持调度类与业务类职责分离）。
 * 单轮失败不影响下一轮。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProtocolReconcileScheduler {

    private final ProtocolReconcileService reconcileService;

    /**
     * 每 60s 一轮对账；启动延迟 60s，等待节点心跳注册与首轮同步状态上报稳定。
     */
    @Scheduled(fixedDelay = 60000L, initialDelay = 60000L)
    public void reconcile() {
        try {
            reconcileService.reconcile();
        } catch (Exception e) {
            log.warn("协议对账本轮异常（下一轮自动重试）: {}", e.getMessage());
        }
    }
}
