package com.cas.access.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * iot-parser 数据解析模块启动类（骨架）。
 *
 * 职责：消费 RocketMQ 中由 iot-access 投递的设备数据，解析后保存入库。
 * 当前为骨架，MQ 消费与解析入库逻辑后续按协议按需实现。
 *
 * @author yjh_c
 */
@Slf4j
@SpringBootApplication
public class ParserApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ParserApplication.class);
        System.out.println("WebApplicationType: " + app.getWebApplicationType());
        System.out.println("JDK: " + System.getProperty("java.version"));
        app.run(args);
        log.info("(♥◠‿◠)ﾉﾞ  iot-parser 数据解析模块启动成功   ლ(´ڡ`ლ)ﾞ ");
    }
}
