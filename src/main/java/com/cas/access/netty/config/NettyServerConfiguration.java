package com.cas.access.netty.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;



/**
 * @author JHYe
 * @date 2023/10/25
 */
@Configuration
public class NettyServerConfiguration {

    @Value("${netty.server.ports}")
    private int[] ports;

    public int[] getPorts() {
        return ports;
    }

    public void setPorts(int[] ports) {
        this.ports = ports;
    }
}
