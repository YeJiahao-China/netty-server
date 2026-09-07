package com.cas.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据桥接日志实体（cluster-admin 管理中心直接查同库）。
 */
@Data
@TableName("bridge_log")
public class BridgeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer serverPort;
    private String serverIp;
    private Integer clientPort;
    private String clientIp;
    private String topicName;
    private Boolean success;
    private Integer retryCount;
    private Integer costMs;
    private String rawData;
    private String errorMsg;
    private LocalDateTime createdAt;
}
