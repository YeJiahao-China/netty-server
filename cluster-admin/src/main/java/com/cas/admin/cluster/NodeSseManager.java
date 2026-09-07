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

    /** 30 分钟超时（0L 在部分容器/代理场景下会导致 async 状态异常） */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 创建新连接，加入连接池 */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            log.debug("SSE 连接完成");
            remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时");
            safeComplete(emitter);
            remove(emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE 连接异常: {}", e.getMessage());
            safeComplete(emitter);
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
            } catch (Exception e) {
                // 客户端已断开 / async 上下文已失效 / 其他内部错误：先 complete 终止 async 上下文，再从池中移除
                // 必须先 safeComplete 再 remove，否则 dispatcherServlet 会继续尝试写入已断开的 socket，产生冗余 IOException
                log.debug("SSE 推送失败，移除连接: {}", e.getMessage());
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
