package com.cas.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 端口-协议绑定实体（cluster-admin 管理中心直接查同库）。
 */
@Data
@TableName("port_protocol_binding")
public class PortProtocolBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer port;
    private String protocolName;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
