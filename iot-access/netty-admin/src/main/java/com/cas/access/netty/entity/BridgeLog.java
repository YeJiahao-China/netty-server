package com.cas.access.netty.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据桥接日志实体：记录每次 TCP→RocketMQ 发送明细。
 */
@Data
@TableName("bridge_log")
public class BridgeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务端 TCP 监听端口 */
    private Integer serverPort;

    /** 服务端 IP */
    private String serverIp;

    /** 客户端远端端口 */
    private Integer clientPort;

    /** 客户端远端 IP */
    private String clientIp;

    /** 目标 RocketMQ topic */
    private String topicName;

    /** 是否发送成功 */
    private Boolean success;

    /** 重试次数（0=首次即成功） */
    private Integer retryCount;

    /** 总耗时毫秒 */
    private Integer costMs;

    /** 原始报文内容（完整保留） */
    private String rawData;

    /** 失败时的异常信息 */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
