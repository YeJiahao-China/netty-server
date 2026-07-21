package com.cas.access.netty.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 端口-协议绑定实体（对应 {@code port_protocol_binding} 表）。
 *
 * @author yjh_c
 */
@Data
@TableName("port_protocol_binding")
public class PortBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** TCP 监听端口 */
    private Integer port;

    /** 协议名（必须已注册） */
    private String protocolName;

    /** 是否启用 */
    private Boolean enabled;

    /** 逻辑删除：0=未删 1=已删 */
    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
