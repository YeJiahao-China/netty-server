package com.cas.cluster.node.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协议 jar 注册表实体（cluster-admin / iot-access 共享）。
 *
 * <p>对应数据库 {@code protocol_jar_registry} 表，记录所有内置 + 外部协议的元数据快照。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@link #source}：{@code builtin}=内置协议 / {@code external}=外部 jar</li>
 *   <li>{@link #status}：INIT / REGISTERED / FAILED / UNLOADED，卸载时置为 UNLOADED，记录保留</li>
 *   <li>{@link #jarBytes}：外部协议 jar 二进制内容，供新节点从 DB 同步</li>
 * </ul>
 *
 * <p>注：本地 jar 文件路径不再存表，由各节点根据 {@code netty.server.protocol.jar-dir}
 * 配置 + 协议名推导为 {@code <jarDir>/<name>.jar}，避免跨节点部署（Linux/Windows）路径不一致。
 *
 * <p>列名与字段名的下划线/驼峰映射由 mybatis-plus 的 map-underscore-to-camel-case 处理。
 * {@code createdAt}/{@code updatedAt} 的自动填充由各模块的 {@code MetaObjectHandler} 处理（如存在）。
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

    /** Provider 实现类全限定名 */
    private String providerClass;

    /** 协议 jar 二进制内容（供新节点从 DB 同步） */
    private byte[] jarBytes;

    /** 注册状态: INIT / REGISTERED / FAILED / UNLOADED */
    private String status;

    /** 加载时间 */
    private LocalDateTime loadedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
