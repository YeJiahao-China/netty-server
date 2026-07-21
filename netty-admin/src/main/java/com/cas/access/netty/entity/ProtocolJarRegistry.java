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
 * 协议 jar 注册表实体。
 *
 * <p>对应数据库 {@code protocol_jar_registry} 表，记录所有内置 + 外部协议的元数据快照。
 * 由 {@code ProtocolBootstrap} 在协议注册时同步落库。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@link #source}：{@code builtin}=内置协议 / {@code external}=外部 jar</li>
 *   <li>{@link #jarPath}：仅外部协议有值</li>
 *   <li>{@link #deleted}：逻辑删除字段，1=已删（MyBatis-Plus 自动过滤）</li>
 * </ul>
 *
 * @author yjh_c
 */
@Data
@TableName("protocol_jar_registry")
public class ProtocolJarRegistry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议唯一标识（如 hj212 / echo） */
    private String name;

    /** 协议版本 */
    private String version;

    /** 协议描述 */
    private String description;

    /** 来源：builtin / external */
    private String source;

    /** jar 文件绝对路径；内置为 null */
    private String jarPath;

    /** Provider 实现类全限定名 */
    private String providerClass;

    /** 是否活跃 */
    private Boolean active;

    /** 逻辑删除：0=未删 1=已删 */
    @TableLogic
    private Integer deleted;

    /** 加载时间 */
    private LocalDateTime loadedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
