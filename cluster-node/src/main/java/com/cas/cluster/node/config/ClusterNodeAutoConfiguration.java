package com.cas.cluster.node.config;

import com.cas.cluster.node.runner.NodeRegistrar;
import com.cas.cluster.node.schedule.HeartbeatScheduler;
import com.cas.cluster.node.service.ClusterNodeService;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集群节点自动装配。
 * <p>两 app（iot-access / iot-parser）仅需依赖 cluster-node，本类即自动生效，无需改动主类：</p>
 * <ul>
 *   <li>@MapperScan：注册 com.cas.cluster.node.mapper（与 access 既有 @MapperScan 叠加，互不影响）</li>
 *   <li>@EnableScheduling：开启 @Scheduled（两 app 主类均未开启）</li>
 *   <li>TaskScheduler：JDK 21 虚拟线程调度器（覆盖 Boot 默认单线程调度器，所有 @Scheduled 任务在虚拟线程上执行）</li>
 *   <li>@EnableConfigurationProperties：绑定 cluster.node.* </li>
 *   <li>@Import：显式注册 service/identity/registrar/scheduler（共享包不在 app 扫描范围内）</li>
 *   <li>@PropertySource：加载各 app 的 cluster-node.properties 以区分 type，缺失则跳过注册</li>
 * </ul>
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(ClusterNodeProperties.class)
@MapperScan("com.cas.cluster.node.mapper")
@Import({ClusterNodeService.class, NodeIdentity.class, NodeRegistrar.class, HeartbeatScheduler.class})
@PropertySource(value = "classpath:cluster-node.properties", ignoreResourceNotFound = true)
public class ClusterNodeAutoConfiguration {

    static final Logger log = LoggerFactory.getLogger(ClusterNodeAutoConfiguration.class);

    /**
     * 虚拟线程版 TaskScheduler（JDK 21+）。
     * <p>标记 @Primary：覆盖 Spring Boot 默认的单线程 ConcurrentTaskScheduler，
     * 使所有 @Scheduled 任务（心跳、失效扫描，以及 app 内其他未来的定时任务）都跑在虚拟线程上。</p>
     * <p>实现要点（JDK 纯能力，不依赖 Spring 内部 setter）：
     * <ol>
     *   <li>内部 carrier 调度器 = 普通 ThreadPoolTaskScheduler，poolSize=1（只负责"按时间点触发"）</li>
     *   <li>外层包装：每次"触发点"一到，立即把实际 runnable 提交到 {@link Executors#newVirtualThreadPerTaskExecutor()}，
     *       carrier 立刻返回，不阻塞；因此多个 @Scheduled 任务可并发执行</li>
     *   <li>虚拟线程命名 vt-sched-N + 未捕获异常兜底日志；carrier 前缀 sched-c- 便于线程 dump 区分</li>
     *   <li>进程退出时共享虚拟线程 ExecutorService 由容器 close 回调一起 shutdown</li>
     * </ol>
     * </p>
     */
    @Bean
    @Primary
    public TaskScheduler clusterNodeTaskScheduler() {
        // 1) 内部 carrier：1 条平台线程，只管按时间点触发
        ThreadPoolTaskScheduler carrier = new ThreadPoolTaskScheduler();
        carrier.setPoolSize(1);
        carrier.setThreadNamePrefix("sched-c-");
        carrier.setErrorHandler(t -> log.error("carrier调度器异常: err={}", t.getMessage(), t));
        carrier.afterPropertiesSet();

        // 2) 虚拟线程执行器（每个任务=一个虚拟线程，JVM 全局承载）
        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong seq = new AtomicLong(0L);

        return new TaskScheduler() {

            /* ---- 两个 @Scheduled 真正会调用的入口（cron / fixedDelay / fixedRate 统一走这两个） ---- */

            @Override
            public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
                return carrier.schedule(wrap(task), trigger);
            }

            @Override
            public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
                return carrier.schedule(wrap(task), startTime);
            }

            /* ---- 以下为 TaskScheduler / TaskExecutor 其余实现：统一 wrap 后交给 carrier ---- */

            @Override
            public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
                return carrier.schedule(wrap(task), startTime);
            }

            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Date startTime, long period) {
                return carrier.scheduleAtFixedRate(wrap(task), startTime, period);
            }

            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
                return carrier.scheduleAtFixedRate(wrap(task), startTime, period);
            }

            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
                return carrier.scheduleAtFixedRate(wrap(task), period);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Date startTime, long delay) {
                return carrier.scheduleWithFixedDelay(wrap(task), startTime, delay);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
                return carrier.scheduleWithFixedDelay(wrap(task), startTime, delay);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
                return carrier.scheduleWithFixedDelay(wrap(task), delay);
            }

            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
                return carrier.scheduleAtFixedRate(wrap(task), period);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                return carrier.scheduleWithFixedDelay(wrap(task), delay);
            }

            /**
             * 停机关闭：carrier 与 vtExecutor 均为局部变量（lambda 闭包持有），
             * Spring 对 @Bean 默认 "(inferred)" destroy 会自动调用本方法——
             * 否则 carrier 的非 daemon 线程 park 在定时队列上无人关闭，阻塞 JVM 优雅退出。
             */
            public void close() {
                carrier.shutdown();      // 停止后续触发，已注册的定时任务取消
                vtExecutor.shutdown();   // 优雅模式：允许在跑的虚拟线程任务执行完
                log.info("clusterNodeTaskScheduler 已关闭（carrier 触发器 + 虚拟线程执行器）");
            }

            /** 包装：将用户任务放到虚拟线程里执行（carrier 只做定时触发，立刻返回） */
            Runnable wrap(Runnable raw) {
                return () -> {
                    // carrier 线程立刻 submit 到虚拟线程，不等待完成（fire-and-forget）；
                    // 对 fixedDelay 语义："delay 按虚拟线程里的任务真实完成时间"如果需要可改为 .get()，
                    // 但对当前心跳/扫描场景，并发 fire 更符合虚拟线程初衷。
                    vtExecutor.submit(wrapOnlyName(raw));
                };
            }

            /** 只给虚拟线程命个名 + 未捕获异常打日志 */
            Runnable wrapOnlyName(Runnable raw) {
                long id = seq.incrementAndGet();
                String name = "vt-sched-" + id;
                return () -> {
                    Thread t = Thread.currentThread();
                    String origin = t.getName();
                    try {
                        t.setName(name);
                        raw.run();
                    } catch (Throwable th) {
                        log.error("虚拟调度任务异常: thread={}, err={}", name, th.getMessage(), th);
                        if (th instanceof RuntimeException re) throw re;
                        if (th instanceof Error e) throw e;
                        throw new RuntimeException(th);
                    } finally {
                        t.setName(origin);
                    }
                };
            }
        };
    }
}
