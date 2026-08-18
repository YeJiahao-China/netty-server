package com.cas.access.netty.mq;

import com.cas.access.netty.entity.BridgeLog;
import com.cas.access.netty.entity.PortTopicBinding;
import com.cas.access.netty.protocol.MessageBridge;
import com.cas.access.netty.service.BridgeLogService;
import com.cas.access.netty.service.PortTopicService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * RocketMQ 数据桥接实现：
 * <ol>
 *   <li>异步提交到业务线程池，不阻塞 Netty IO 线程；</li>
 *   <li>发送失败采用 Spring RetryTemplate + 指数退避策略重试（初始 100ms，倍数 2.0，最大 4 次）；</li>
 *   <li>每次桥接成功/失败都落库 bridge_log，失败时保留完整原始报文和客户端/服务端地址。</li>
 * </ol>
 * 使用 rocketmq-client-java 5.0.7（gRPC 协议）。
 */

@Slf4j
@Component
public class RocketMQMessageBridge implements MessageBridge {

    @Value("${rocketmq.endpoint:127.0.0.1:8081}")
    private String endpoint;

    @Resource(name = "bridgeSendVirtualExecutor")
    private ExecutorService bridgeSendVirtualExecutor;

    @Resource(name = "bridgeConcurrencyLimiter")
    private Semaphore concurrencyLimiter;

    @Resource(name = "bridgeRetryTemplate")
    private RetryTemplate retryTemplate;

    @Resource
    private PortTopicService portTopicService;

    @Resource
    private BridgeLogService bridgeLogService;

    private volatile Producer producer;

    @PostConstruct
    public void init() {
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(endpoint)
                    .build();
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setTopics()
                    .build();
            log.info("RocketMQ Producer 启动成功, endpoint={}", endpoint);
        } catch (Exception e) {
            log.error("RocketMQ Producer 启动失败, endpoint={}", endpoint, e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
                log.info("RocketMQ Producer 已关闭");
            } catch (Exception e) {
                log.warn("RocketMQ Producer 关闭异常", e);
            }
        }
    }

    @Override
    public void send(final int serverPort, final String serverIp,
                     final int clientPort, final String clientIp,
                     final String data) {
        // 1、查询端口绑定
        PortTopicBinding binding = portTopicService.selectByPort(serverPort);
        // 1. 尝试获取许可（限流），等同于原线程池的队列容量控制
        if (!concurrencyLimiter.tryAcquire()) {
            log.warn("数据桥接并发数已满，触发限流: serverPort={}, client={}:{}, availablePermits={}",
                    serverPort, clientIp, clientPort, concurrencyLimiter.availablePermits());

            // 【核心优化】：限流被拒时，异步记录失败日志到数据库。
            // 注意：必须放入虚拟线程异步执行，绝不能阻塞当前的 Netty IO 线程！
            // 同时，这里直接提交，不需要获取 Semaphore 许可，避免许可泄漏。
            bridgeSendVirtualExecutor.execute(() -> saveRejectedLog(serverPort, serverIp, clientPort, clientIp, data, binding));
            return;
        }
        log.info("成功获取数据桥接许可，剩余可用Limiter={}", concurrencyLimiter.availablePermits());
        // 2. 提交到虚拟线程异步执行，Netty IO 线程立即返回
        bridgeSendVirtualExecutor.execute(() -> {
            try {
                doBridge(serverPort, serverIp, clientPort, clientIp, data, binding);
            } finally {
                // 3. 无论成功失败，必须释放许可！防止并发数泄漏
                concurrencyLimiter.release();
                log.info("数据桥接结束释放许可，剩余可用Limiter={}", concurrencyLimiter.availablePermits());
            }
        });
    }

    /**
     * 异步记录被限流丢弃的日志。
     * 独立抽取方法，避免在 send 方法中代码过于臃肿。
     */
    private void saveRejectedLog(int serverPort, String serverIp, int clientPort, String clientIp, String data, PortTopicBinding portTopicBinding) {
        try {
            BridgeLog bridgeLog = new BridgeLog();
            bridgeLog.setServerPort(serverPort);
            bridgeLog.setServerIp(serverIp);
            bridgeLog.setClientPort(clientPort);
            bridgeLog.setClientIp(clientIp);
            bridgeLog.setRawData(data);
            bridgeLog.setTopicName(portTopicBinding.getTopicName());
            bridgeLog.setSuccess(false);
            bridgeLog.setRetryCount(0);
            bridgeLog.setCostMs(0);
            bridgeLog.setErrorMsg("触发并发限流，任务被丢弃");
            bridgeLog.setCreatedAt(LocalDateTime.now());

            bridgeLogService.save(bridgeLog);
        } catch (Exception e) {
            // 兜底：如果连限流日志都落库失败，只打印 error 日志，避免异常抛出影响系统
            log.error("限流失败日志落库异常: server={}:{} client={}:{} error={}",
                    serverIp, serverPort, clientIp, clientPort, e.getMessage(), e);
        }
    }

    /**
     * 数据桥接主流程：查询绑定 → 校验 Producer → RetryTemplate 重试发送 → 落库日志。
     * 外层做了兜底 try-catch，确保任何情况下都至少写入一条失败的 bridge_log。
     */
    private void doBridge(int serverPort, String serverIp,
                          int clientPort, String clientIp,
                          String data, PortTopicBinding binding) {
        long totalStart = System.currentTimeMillis();
        BridgeLog bridgeLog = new BridgeLog();
        bridgeLog.setServerPort(serverPort);
        bridgeLog.setServerIp(serverIp);
        bridgeLog.setClientPort(clientPort);
        bridgeLog.setClientIp(clientIp);
        bridgeLog.setRawData(data);

        try {
            if (binding == null || !Boolean.TRUE.equals(binding.getEnabled())) {
                finishWithFailure(bridgeLog, totalStart, 0, "无可用绑定或已禁用", null);
                log.warn("数据桥接跳过: serverPort={} client={}:{} 无可用绑定或已禁用",
                        serverPort, clientIp, clientPort);
                return;
            }
            String topicName = binding.getTopicName();
            bridgeLog.setTopicName(topicName);

            // 2、Producer 未就绪
            if (producer == null) {
                finishWithFailure(bridgeLog, totalStart, 0, "Producer 未初始化", null);
                log.error("数据桥接失败: server={}:{} client={}:{} topic={} Producer未就绪 raw={}",
                        serverIp, serverPort, clientIp, clientPort, topicName, data);
                return;
            }

            // 3、使用 RetryTemplate 执行发送（指数退避重试）
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            final byte[] body = data.getBytes(StandardCharsets.UTF_8);
            final int[] retryCountHolder = {0};

            try {
                retryTemplate.execute((RetryCallback<Void, Exception>) context -> {
                    retryCountHolder[0] = context.getRetryCount();
                    Message message = provider.newMessageBuilder()
                            .setTopic(topicName)
                            .setKeys(String.valueOf(serverPort))
                            .setBody(body)
                            .build();
                    producer.send(message);
                    return null;
                });

                // 发送成功
                long cost = System.currentTimeMillis() - totalStart;
                bridgeLog.setSuccess(true);
                bridgeLog.setRetryCount(retryCountHolder[0]);
                bridgeLog.setCostMs((int) cost);
                bridgeLog.setErrorMsg(null);
                bridgeLog.setCreatedAt(LocalDateTime.now());
                bridgeLogService.save(bridgeLog);

                log.info("数据桥接成功: server={}:{} client={}:{} topic={} retry={} cost={}ms dataLen={}",
                        serverIp, serverPort, clientIp, clientPort, topicName,
                        retryCountHolder[0], cost, data.length());

            } catch (Exception e) {
                // 重试耗尽，最终失败
                long cost = System.currentTimeMillis() - totalStart;
                String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                bridgeLog.setSuccess(false);
                bridgeLog.setRetryCount(retryCountHolder[0]);
                bridgeLog.setCostMs((int) cost);
                bridgeLog.setErrorMsg(errorMsg);
                bridgeLog.setCreatedAt(LocalDateTime.now());
                bridgeLogService.save(bridgeLog);

                log.error("数据桥接失败重试次数耗尽(已重试{}次): server={}:{} client={}:{} topic={} cost={}ms error={} raw={}",
                        retryCountHolder[0], serverIp, serverPort, clientIp, clientPort,
                        topicName, cost, errorMsg, data);
            }

        } catch (Throwable t) {
            // 兜底：任何未预期的异常（例如查库异常、loadService 失败等）
            long cost = System.currentTimeMillis() - totalStart;
            bridgeLog.setSuccess(false);
            bridgeLog.setRetryCount(0);
            bridgeLog.setCostMs((int) cost);
            bridgeLog.setErrorMsg(t.getClass().getSimpleName() + ": " + t.getMessage());
            bridgeLog.setCreatedAt(LocalDateTime.now());
            try {
                bridgeLogService.save(bridgeLog);
            } catch (Exception saveEx) {
                log.error("数据桥接兜底异常，且落库失败: server={}:{} client={}:{} raw={} saveError={} bridgeError={}",
                        serverIp, serverPort, clientIp, clientPort, data,
                        saveEx.getMessage(), t.getMessage(), t);
                return;
            }
            log.error("数据桥接发生未预期异常: server={}:{} client={}:{} cost={}ms raw={}",
                    serverIp, serverPort, clientIp, clientPort, cost, data, t);
        }
    }

    @Override
    public boolean resend(final int serverPort, final String serverIp,
                          final int clientPort, final String clientIp,
                          final String topicName, final String data) {
        long start = System.currentTimeMillis();

        // 限流：重入队也需要限流，防止堆积大量失败任务时冲垮系统
        if (!concurrencyLimiter.tryAcquire()) {
            log.warn("重入队触发限流，跳过: server={}:{} client={}:{} topic={}",
                    serverIp, serverPort, clientIp, clientPort, topicName);
            return false;
        }

        try {
            // Producer 未就绪
            if (producer == null) {
                log.error("重入队失败: Producer未就绪 server={}:{} client={}:{} topic={}",
                        serverIp, serverPort, clientIp, clientPort, topicName);
                return false;
            }

            ClientServiceProvider provider = ClientServiceProvider.loadService();
            final byte[] body = data.getBytes(StandardCharsets.UTF_8);

            retryTemplate.execute((RetryCallback<Void, Exception>) context -> {
                Message message = provider.newMessageBuilder()
                        .setTopic(topicName)
                        .setKeys(String.valueOf(serverPort))
                        .setBody(body)
                        .build();
                producer.send(message);
                return null;
            });

            long cost = System.currentTimeMillis() - start;
            log.info("重入队成功: server={}:{} client={}:{} topic={} cost={}ms",
                    serverIp, serverPort, clientIp, clientPort, topicName, cost);
            return true;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("重入队失败(已重试耗尽): server={}:{} client={}:{} topic={} cost={}ms error={}",
                    serverIp, serverPort, clientIp, clientPort, topicName, cost, e.getMessage());
            return false;
        } finally {
            concurrencyLimiter.release();
        }
    }

    /**
     * 快速填充失败日志并落库（用于绑定缺失、Producer 未就绪等前置校验失败场景）。
     */
    private void finishWithFailure(BridgeLog bridgeLog, long totalStart,
                                   int retryCount, String errorMsg, String topicName) {
        long cost = System.currentTimeMillis() - totalStart;
        bridgeLog.setTopicName(topicName);
        bridgeLog.setSuccess(false);
        bridgeLog.setRetryCount(retryCount);
        bridgeLog.setCostMs((int) cost);
        bridgeLog.setErrorMsg(errorMsg);
        bridgeLog.setCreatedAt(LocalDateTime.now());
        bridgeLogService.save(bridgeLog);
    }
}