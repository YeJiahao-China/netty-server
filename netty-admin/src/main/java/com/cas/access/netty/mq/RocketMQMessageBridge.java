package com.cas.access.netty.mq;

import com.cas.access.netty.entity.PortTopicBinding;
import com.cas.access.netty.protocol.MessageBridge;
import com.cas.access.netty.service.PortTopicService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 数据桥接实现：将 TCP 接收到的数据发送到端口绑定的 topic。
 * 使用 rocketmq-client-java 5.0.7（gRPC 协议）。
 */
@Slf4j
@Component
public class RocketMQMessageBridge implements MessageBridge {

    @Value("${rocketmq.endpoint:127.0.0.1:8081}")
    private String endpoint;

    @Value("${rocketmq.producer.group:netty-server-bridge}")
    private String producerGroup;

    @Resource
    private PortTopicService portTopicService;

    private Producer producer;

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
    public boolean send(int port, String data) {
        // 1、查询端口绑定的 topic
        PortTopicBinding binding = portTopicService.selectByPort(port);
        if (binding == null || !Boolean.TRUE.equals(binding.getEnabled())) {
            log.warn("数据桥接跳过: port={} 无可用绑定或已禁用", port);
            return false;
        }

        String topicName = binding.getTopicName();
        if (topicName == null || topicName.trim().isEmpty()) {
            log.warn("数据桥接跳过: port={}, topicName 为空", port);
            return false;
        }

        // 2、Producer 未就绪
        if (producer == null) {
            log.error("数据桥接失败: port={}, topic={}, Producer 未初始化", port, topicName);
            return false;
        }

        // 3、发送消息
        long start = System.currentTimeMillis();
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message message = provider.newMessageBuilder()
                    .setTopic(topicName)
                    .setKeys(String.valueOf(port))
                    .setBody(data.getBytes(StandardCharsets.UTF_8))
                    .build();

            producer.send(message);
            long cost = System.currentTimeMillis() - start;
            log.info("数据桥接成功: port={}, topic={}, cost={}ms, dataLen={}", port, topicName, cost, data.length());
            return true;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("数据桥接失败: port={}, topic={}, cost={}ms, error={}", port, topicName, cost, e.getMessage(), e);
            return false;
        }
    }
}
