package com.cas.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 端口-主题绑定实体（cluster-admin 管理中心直接查同库）。
 */
@Data
@TableName("port_topic_binding")
public class PortTopicBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer port;
    private String topicName;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
