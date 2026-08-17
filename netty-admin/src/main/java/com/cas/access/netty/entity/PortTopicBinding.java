package com.cas.access.netty.entity;


import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("port_topic_binding")
public class PortTopicBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** TCP 监听端口 */
    private Integer port;

    /** topic名称 */
    private String topicName;

    /** 是否启用 */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

}
