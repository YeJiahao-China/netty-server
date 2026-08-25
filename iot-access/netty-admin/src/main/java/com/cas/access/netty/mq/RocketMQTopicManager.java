package com.cas.access.netty.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RocketMQTopicManager {

    // 你的 Dashboard 地址（根据你的实际端口修改，通常是 8082 或 8080）
    @Value("${rocketmq.dashboard.url:http://127.0.0.1:8082}")
    private String dashboardUrl;

    // 集群名称（通常是 DefaultCluster，必须与 Dashboard Cluster 页面显示的一致）
    @Value("${rocketmq.cluster.name:DefaultCluster}")
    private String clusterName;

    private final Set<String> existingTopics = ConcurrentHashMap.newKeySet();

    // 使用 JDK 11+ 自带的 HttpClient，线程安全且高效
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 自动创建 Topic (JSON REST API 降维打击版)
     */
    public void createTopicIfNotExist(String topicName) {
        if (existingTopics.contains(topicName)) {
            return;
        }

        try {
            // 【核心修正 1】：构造符合 TopicConfigInfo 的 JSON 请求体
            // 注意：Dashboard 源码要求 clusterNameList 或 brokerNameList 必须是数组，且不能为空
            String jsonBody = String.format("{" +
                    "\"topicName\":\"%s\"," +
                    "\"clusterNameList\":[\"%s\"]," +
                    "\"writeQueueNums\":4," +
                    "\"readQueueNums\":4," +
                    "\"perm\":6," +
                    "\"order\":false" +
                    "}", topicName, clusterName);

            // 【核心修正 2】：使用真实的 API 路径 /topic/createOrUpdate.do
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboardUrl + "/topic/createOrUpdate.do"))
                    .header("Content-Type", "application/json") // 必须指定为 JSON
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Dashboard 成功时通常返回 true (HTTP 200, body="true")
            if (response.statusCode() == 200) {
                log.info("通过 Dashboard API 成功创建 Topic: {}", topicName);
                existingTopics.add(topicName);
            } else {
                log.error("Dashboard API 返回异常: code={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("通过 Dashboard API 创建 Topic 失败: {}", e.getMessage(), e);
        }
    }
}