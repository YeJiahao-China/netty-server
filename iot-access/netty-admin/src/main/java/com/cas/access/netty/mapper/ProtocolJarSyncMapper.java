package com.cas.access.netty.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * protocol_jar_registry 表的 jar 仓库同步 Mapper。
 * <p>供 {@link com.cas.access.netty.service.ProtocolJarSyncService} 使用，
 * 查询 status=REGISTERED 的协议 jar_bytes 供新节点同步。</p>
 */
public interface ProtocolJarSyncMapper {

    /** 查询所有 REGISTERED 状态的外部协议 jar */
    @Select("SELECT name, jar_bytes, jar_path FROM protocol_jar_registry WHERE status = 'REGISTERED' AND jar_bytes IS NOT NULL")
    List<Map<String, Object>> selectRegisteredJars();

    /** 按协议名查询单条 jar_bytes */
    @Select("SELECT name, jar_bytes, jar_path FROM protocol_jar_registry WHERE name = #{name} LIMIT 1")
    Map<String, Object> selectJarByName(@Param("name") String name);

    @Update("UPDATE protocol_jar_registry SET jar_path = #{jarPath}, updated_at = CURRENT_TIMESTAMP WHERE name = #{protocolName}")
    int updateJarPath(@Param("protocolName") String protocolName, @Param("jarPath") String jarPath);
}

