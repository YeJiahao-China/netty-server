package com.cas.admin.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocketMQ Topic 创建工具：通过 Dashboard REST API 创建 topic。
 * 仅用 JDK HttpClient，不需要 rocketmq-client 依赖。
 */
@Slf4j
@Component
public class RocketMQTopicManager {

    @Value("${rocketmq.dashboard.url:http://127.0.0.1:8082}")
    private String dashboardUrl;

    @Value("${rocketmq.cluster.name:DefaultCluster}")
    private String clusterName;

    private final Set<String> existingTopics = ConcurrentHashMap.newKeySet();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void createTopicIfNotExist(String topicName) throws Exception {
        if (existingTopics.contains(topicName)) {
            return;
        }

        try {
            String jsonBody = String.format("{" +
                    "\"topicName\":\"%s\"," +
                    "\"clusterNameList\":[\"%s\"]," +
                    "\"writeQueueNums\":4," +
                    "\"readQueueNums\":4," +
                    "\"perm\":6," +
                    "\"order\":false" +
                    "}", topicName, clusterName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboardUrl + "/topic/createOrUpdate.do"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("通过 Dashboard API 成功创建 Topic: {}", topicName);
                existingTopics.add(topicName);
            } else {
                log.error("Dashboard API 返回异常: code={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException(response.body());
            }
        } catch (Exception e) {
            log.error("通过 Dashboard API 创建 Topic 失败: {}", e.getMessage(), e);
            throw e;
        }
    }
}
