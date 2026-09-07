package com.cas.cluster.node.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 本节点身份解析：启动时确定 host/port/nodeId/name，供注册器与心跳调度器共用。
 */
@Slf4j
@Getter
@Component
public class NodeIdentity {

    private final ClusterNodeProperties props;

    @Value("${server.port:0}")
    private int serverPort;

    private String host;
    private int port;
    private String nodeId;
    private String name;

    public NodeIdentity(ClusterNodeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void resolve() {
        this.port = serverPort;
        this.host = (props.getHost() != null && !props.getHost().isBlank())
                ? props.getHost() : detectHost();
        this.nodeId = host + ":" + port;
        this.name = (props.getName() != null && !props.getName().isBlank())
                ? props.getName() : nodeId;
        log.info("集群节点身份解析完成: nodeId={}, type={}, host={}, port={}",
                nodeId, props.getType(), host, port);
    }

    /** 是否配置了节点类型（未配置则不参与注册/心跳） */
    public boolean isConfigured() {
        return props.getType() != null && !props.getType().isBlank();
    }

    /** 探测本机首个非回环、非 down 的 IPv4 地址；探测失败回退 127.0.0.1 */
    private String detectHost() {
        try {
            for (Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces(); nics.hasMoreElements(); ) {
                NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                for (Enumeration<InetAddress> addrs = nic.getInetAddresses(); addrs.hasMoreElements(); ) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("自动探测本机 IP 失败，回退到 127.0.0.1: {}", e.getMessage());
        }
        return "127.0.0.1";
    }
}
