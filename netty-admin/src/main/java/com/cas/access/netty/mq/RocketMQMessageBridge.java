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

// 【修复】：Spring Boot 3 必须统一使用 jakarta 包，防止 javax 冲突报错
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class RocketMQMessageBridge implements MessageBridge {

    // 【修改】：gRPC 客户端直连 Proxy (8081)
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

    @Resource
    private RocketMQTopicManager rocketMQTopicManager;

    // 【修改】：替换为 gRPC 的 Producer 接口
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
                    .build();
            log.info("RocketMQ gRPC Producer 启动成功, endpoint={}", endpoint);
        } catch (Exception e) {
            log.error("RocketMQ Producer 启动失败, endpoint={}", endpoint, e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close(); // gRPC 客户端使用 close() 关闭
                log.info("RocketMQ Producer 已关闭");
            } catch (Exception e) {
                log.warn("RocketMQ Producer 关闭异常", e);
            }
        }
    }

    /**
     * 常规数据发送
     * @param serverPort 服务端 TCP 监听端口
     * @param serverIp   服务端 IP
     * @param clientPort 客户端远端端口
     * @param clientIp   客户端远端 IP
     * @param data       原始数据内容
     */
    @Override
    public void send(final int serverPort, final String serverIp,
                     final int clientPort, final String clientIp,
                     final String data) {
        // 1、查询端口绑定
        PortTopicBinding binding = portTopicService.selectAvailableByPort(serverPort);
        bridgeSendVirtualExecutor.execute(() -> doBridge(serverPort, serverIp, clientPort, clientIp, data, binding));


        // 2.常规的数据桥接不需要限流，暂时注释掉 ###尝试获取许可（限流），等同于原线程池的队列容量控制
//        if (!concurrencyLimiter.tryAcquire()) {
//            log.warn("数据桥接并发数已满，触发限流: serverPort={}, client={}:{}, availablePermits={}",
//                    serverPort, clientIp, clientPort, concurrencyLimiter.availablePermits());
//
//            // 【核心优化】：限流被拒时，异步记录失败日志到数据库。
//            bridgeSendVirtualExecutor.execute(() -> saveRejectedLog(serverPort, serverIp, clientPort, clientIp, data, binding));
//            return;
//        }

        // 3. 提交到虚拟线程异步执行，Netty IO 线程立即返回
//        bridgeSendVirtualExecutor.execute(() -> {
//            try {
//                doBridge(serverPort, serverIp, clientPort, clientIp, data, binding);
//            } finally {
//                // 4. 无论成功失败，必须释放许可！防止并发数泄漏
//                concurrencyLimiter.release();
//            }
//        });
    }

    @Override
    public boolean resend(final int serverPort, final String serverIp,
                          final int clientPort, final String clientIp,
                          final String topicName, final String data) {

        if (!concurrencyLimiter.tryAcquire()) {
            log.warn("重投递触发限流，跳过: server={}:{} client={}:{} topic={}",
                    serverIp, serverPort, clientIp, clientPort, topicName);
            return false;
        }
        try {
            if (producer == null) {
                log.error("重投递失败: Producer未就绪 server={}:{} client={}:{} topic={}",
                        serverIp, serverPort, clientIp, clientPort, topicName);
                return false;
            }

            final byte[] body = data.getBytes(StandardCharsets.UTF_8);
            ClientServiceProvider provider = ClientServiceProvider.loadService();

            retryTemplate.execute((RetryCallback<Void, Exception>) context -> {
                // 【修改】：使用 gRPC 客户端的 Message 构建方式
                Message message = provider.newMessageBuilder()
                        .setTopic(topicName)
                        .setKeys(String.valueOf(serverPort))
                        .setBody(body)
                        .build();
                producer.send(message);
                return null;
            });

            log.info("重投递成功: server={}:{} client={}:{} topic={} ",
                    serverIp, serverPort, clientIp, clientPort, topicName);
            return true;

        } catch (Exception e) {
            log.error("重投递失败(已重试耗尽/未达到重试标准): server={}:{} client={}:{} topic={} error={}",
                    serverIp, serverPort, clientIp, clientPort, topicName, e.getMessage());
            return false;
        } finally {
            concurrencyLimiter.release();
            log.info("释放重投递许可, 剩余Limiter={}", concurrencyLimiter.availablePermits());
        }
    }

    /**
     * 异步记录被限流丢弃的日志。
     */
    private void saveRejectedLog(int serverPort, String serverIp, int clientPort, String clientIp, String data, PortTopicBinding portTopicBinding) {
        try {
            BridgeLog bridgeLog = new BridgeLog();
            bridgeLog.setServerPort(serverPort);
            bridgeLog.setServerIp(serverIp);
            bridgeLog.setClientPort(clientPort);
            bridgeLog.setClientIp(clientIp);
            bridgeLog.setRawData(data);
            // 【修复】：防止 binding 为 null 时引发 NullPointerException
            bridgeLog.setTopicName(portTopicBinding != null ? portTopicBinding.getTopicName() : null);
            bridgeLog.setSuccess(false);
            bridgeLog.setRetryCount(0);
            bridgeLog.setCostMs(0);
            bridgeLog.setErrorMsg("触发并发限流，任务被丢弃");
            bridgeLog.setCreatedAt(LocalDateTime.now());

            bridgeLogService.save(bridgeLog);
        } catch (Exception e) {
            log.error("限流失败日志落库异常: server={}:{} client={}:{} error={}",
                    serverIp, serverPort, clientIp, clientPort, e.getMessage(), e);
        }
    }

    /**
     * 数据桥接主流程：查询绑定 → 校验 Producer → RetryTemplate 重试发送 → 落库日志。
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

            if (producer == null) {
                finishWithFailure(bridgeLog, totalStart, 0, "Producer 未初始化", null);
                log.error("数据桥接失败: server={}:{} client={}:{} topic={} Producer未就绪 raw={}",
                        serverIp, serverPort, clientIp, clientPort, topicName, data);
                return;
            }

            // 【恢复】：动态创建 Topic (通过 HTTP 调用 Dashboard)
//            rocketMQTopicManager.createTopicIfNotExist(topicName);

            final byte[] body = data.getBytes(StandardCharsets.UTF_8);
            final int[] retryCountHolder = {0};

            // gRPC 客户端的 Provider
            ClientServiceProvider provider = ClientServiceProvider.loadService();

            try {
                retryTemplate.execute((RetryCallback<Void, Exception>) context -> {
                    retryCountHolder[0] = context.getRetryCount();

                    // 【修改】：使用 gRPC 客户端的 Message 构建方式
                    Message message = provider.newMessageBuilder()
                            .setTopic(topicName)
                            .setKeys(String.valueOf(serverPort))
                            .setBody(body)
                            .build();

                    // gRPC 发送，失败会自动抛出异常被 RetryTemplate 捕获
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

                log.info("数据桥接成功: server={}:{} client={}:{} topic={} retry={} cost={}ms data={}",
                        serverIp, serverPort, clientIp, clientPort, topicName,
                        retryCountHolder[0], cost, data);

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
            // 兜底：任何未预期的异常
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


    /**
     * 快速填充失败日志并落库
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