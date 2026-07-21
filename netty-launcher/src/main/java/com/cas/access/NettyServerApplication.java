package com.cas.access;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author yjh_c
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.cas.access.netty.mapper")
public class NettyServerApplication {
    public static void main(String[] args) {
//        SpringApplication.run(NettyServerApplication.class, args);
//        log.info("(♥◠‿◠)ﾉﾞ  NettyServer启动成功   ლ(´ڡ`ლ)ﾞ ");
        SpringApplication app = new SpringApplication(NettyServerApplication.class);
        // 打印 web 类型
        System.out.println("WebApplicationType: " + app.getWebApplicationType());
        app.run(args);
        log.info("(♥◠‿◠)ﾉﾞ  NettyServer启动成功   ლ(´ڡ`ლ)ﾞ ");
    }
}
