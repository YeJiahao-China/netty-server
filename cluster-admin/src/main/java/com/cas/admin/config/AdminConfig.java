package com.cas.admin.config;

import com.cas.admin.cluster.NodeBroadcastClient;
import com.cas.admin.service.PrimaryNodeRouter;
import com.cas.cluster.node.service.ClusterNodeService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 管理中心配置：RestClient（用于转发到各节点）、节点选择器、广播客户端、后台任务线程池。
 */
@Configuration
@EnableConfigurationProperties(AdminProxyProperties.class)
public class AdminConfig {

    /**
     * 公用后台任务线程池（虚拟线程，按需创建、不区分业务类别）。
     * 供各类后台异步任务复用（如协议卸载失败节点的指数退避重试、未来的对账任务等），
     * 容器停机时由 Spring 自动 shutdownNow（ destroyMethod ）。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService adminTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RestClient.Builder nodeRestClientBuilder(AdminProxyProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public PrimaryNodeRouter primaryNodeRouter(AdminProxyProperties props, ClusterNodeService service) {
        return new PrimaryNodeRouter(props, service);
    }

    @Bean
    public NodeBroadcastClient nodeBroadcastClient(RestClient.Builder builder, PrimaryNodeRouter router) {
        return new NodeBroadcastClient(builder.build(), router);
    }
}
