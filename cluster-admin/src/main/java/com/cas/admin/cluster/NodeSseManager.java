package com.cas.admin.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接池管理：维护所有前端浏览器的 SseEmitter，统一推送节点状态变化。
 */
@Slf4j
@Component
public class NodeSseManager {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 创建新连接，加入连接池 */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(e -> remove(emitter));
        log.info("SSE 连接建立，当前连接数: {}", emitters.size());
        return emitter;
    }

    /** 向所有连接推送一个事件 */
    public void sendToAll(String eventName, Object data) {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                // 客户端已断开（刷新/关页面），先 complete 再移除，避免 Spring async 容器重复报错
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
