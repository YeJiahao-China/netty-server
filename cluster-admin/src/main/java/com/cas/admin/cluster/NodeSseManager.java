package com.cas.admin.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接池管理：维护所有前端浏览器的 SseEmitter，统一推送节点状态变化。
 */
@Slf4j
@Component
public class NodeSseManager {

    /** 30 分钟超时（0L 在部分容器/代理场景下会导致 async 状态异常） */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 创建新连接，加入连接池 */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时");
            remove(emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE 连接异常: {}", e.getMessage());
            remove(emitter);
        });
        log.info("SSE 连接建立，当前连接数: {}", emitters.size());
        return emitter;
    }

    /** 向所有连接推送一个事件 */
    public void sendToAll(String eventName, Object data) {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                // 客户端已断开或 async 上下文已失效，直接移除，不重复 complete 避免二次异常
                log.debug("SSE 推送失败，移除连接: {}", e.getMessage());
                remove(emitter);
            } catch (Exception e) {
                log.warn("SSE 推送未知异常: {}", e.getMessage());
                safeComplete(emitter);
                remove(emitter);
            }
        }
    }

    private void remove(SseEmitter emitter) {
        if (emitters.remove(emitter)) {
            log.info("SSE 连接断开，当前连接数: {}", emitters.size());
        }
    }

    /** 静默 complete，忽略二次异常 */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
