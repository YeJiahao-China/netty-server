package com.cas.access.netty.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * protocol_jar_registry 表的 jar 仓库同步 Mapper。
 * <p>供 {@link com.cas.access.netty.service.ProtocolJarSyncService} 使用，
 * 查询 status=REGISTERED 的协议 jar_bytes 供新节点同步。</p>
 *
 * <p>注：本地 jar 文件路径不再存表，由各节点根据
 * {@code netty.server.protocol.jar-dir} 配置 + 协议名推导。</p>
 */
public interface ProtocolJarSyncMapper {

    /** 查询所有 REGISTERED 状态的外部协议 jar */
    @Select("SELECT name, jar_bytes FROM protocol_jar_registry WHERE status = 'REGISTERED' AND jar_bytes IS NOT NULL")
    List<Map<String, Object>> selectRegisteredJars();

    /** 按协议名查询单条 jar_bytes */
    @Select("SELECT name, jar_bytes FROM protocol_jar_registry WHERE name = #{name} LIMIT 1")
    Map<String, Object> selectJarByName(@Param("name") String name);
}
