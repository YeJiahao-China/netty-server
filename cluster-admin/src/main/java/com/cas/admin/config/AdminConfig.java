package com.cas.admin.config;

import com.cas.admin.cluster.NodeBroadcastClient;
import com.cas.admin.service.PrimaryNodeRouter;
import com.cas.cluster.node.service.ClusterNodeService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 管理中心配置：RestClient（用于转发到各节点）、节点选择器、广播客户端。
 */
@Configuration
@EnableConfigurationProperties(AdminProxyProperties.class)
public class AdminConfig {

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
