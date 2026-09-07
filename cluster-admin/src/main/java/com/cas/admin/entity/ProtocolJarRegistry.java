package com.cas.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协议 jar 注册表实体（cluster-admin 视角）。
 * <p>用于管理 protocol_jar_registry 表中的 jar_bytes / status / failure_detail 字段。</p>
 */
@Data
@TableName("protocol_jar_registry")
public class ProtocolJarRegistry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String version;

    private String description;

    private String source;

    private String jarPath;

    private String providerClass;

    /** 协议 jar 二进制内容 */
    private byte[] jarBytes;

    /** 注册状态: INIT / REGISTERED / FAILED */
    private String status;

    /** 注册失败明细 (JSON) */
    private String failureDetail;

    private LocalDateTime loadedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
