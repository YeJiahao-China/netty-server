package com.cas.admin;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 统一管理中心（独立 Spring Boot）：页面 SSR + 节点列表 + 广播/代理多节点接口。
 * <p>端口 2312（iot-access=2310、iot-parser=2311）。</p>
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@MapperScan("com.cas.admin.mapper")
public class ClusterAdminApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClusterAdminApplication.class);
        System.out.println("WebApplicationType: " + app.getWebApplicationType());
        System.out.println("JDK: " + System.getProperty("java.version"));
        app.run(args);
        log.info("(♥◠‿◠)ﾉﾞ  管理中心启动成功（:2312）  ლ(´ڡ`ლ)ﾞ ");
    }
}
