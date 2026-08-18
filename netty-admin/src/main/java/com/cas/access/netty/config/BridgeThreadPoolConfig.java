package com.cas.access.netty.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.retry.annotation.EnableRetry;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@EnableAsync
@EnableRetry
@Configuration
public class BridgeThreadPoolConfig {

    /** 线程名前缀 */
    public static final String THREAD_NAME_PREFIX = "bridge-send-virtual-";

    // 虚拟线程不需要 core/max 参数，但需要控制最大并发数（限流）
    // 默认 10000，可根据实际内存和下游 RocketMQ 承受能力调整
    @Value("${rocketmq.producer.bridge-max-concurrent-tasks:10000}")
    private int maxConcurrentTasks;

    @Value("${rocketmq.producer.bridge-retry-max-attempts:6}")
    private int retryMaxAttempts;

    @Value("${rocketmq.producer.bridge-retry-initial-backoff-ms:1000}")
    private long retryInitialBackoffMs;

    @Value("${rocketmq.producer.bridge-retry-multiplier:2.0}")
    private double retryMultiplier;

    @Value("${rocketmq.producer.bridge-retry-max-backoff-ms:60000}")
    private long retryMaxBackoffMs;

    /**
     * 1. 替换为 JDK 21 虚拟线程执行器
     * 优势：遇到 Thread.sleep 或 I/O 阻塞时，自动释放底层系统线程，永不饥饿。
     */
    @Bean("bridgeSendVirtualExecutor")
    public ExecutorService bridgeSendVirtualExecutor() {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(THREAD_NAME_PREFIX, 0) // 线程命名：bridge-send-0, bridge-send-1...
                        .factory()
        );
        log.info("数据桥接业务虚拟线程池初始化完成 (Thread-per-task 模式)");
        return executor;
    }

    /**
     * 2. 引入信号量用于限流（替代原有队列的背压能力）
     * 防止瞬间产生海量重试任务压垮 RocketMQ Broker。
     */
    @Bean("bridgeConcurrencyLimiter")
    public Semaphore bridgeConcurrencyLimiter() {
        return new Semaphore(maxConcurrentTasks);
    }

    /**
     * 3. 数据桥接重试模板
     */
    @Bean("bridgeRetryTemplate")
    public RetryTemplate bridgeRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // 优化：排除 InterruptedException，防止应用停机或线程中断时死循环重试
        Map<Class<? extends Throwable>, Boolean> exceptionMap = new HashMap<>();
        exceptionMap.put(Exception.class, true);
        exceptionMap.put(InterruptedException.class, false);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(retryMaxAttempts, exceptionMap);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(retryInitialBackoffMs);
        backOffPolicy.setMultiplier(retryMultiplier);
        backOffPolicy.setMaxInterval(retryMaxBackoffMs);

        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);

        template.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                return true;
            }

            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                                                         RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("数据桥接第{}次尝试: error={}",
                        context.getRetryCount(),
                        throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context,
                                                       RetryCallback<T, E> callback,
                                                       Throwable throwable) {
                // no-op
            }
        });

        log.info("数据桥接 RetryTemplate 初始化: maxAttempts={}, initialBackoff={}ms, multiplier={}, maxBackoff={}ms",
                retryMaxAttempts, retryInitialBackoffMs, retryMultiplier, retryMaxBackoffMs);
        return template;
    }
}
