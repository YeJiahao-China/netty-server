package com.cas.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.admin.entity.ProtocolJarRegistry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 协议 jar 注册表 Mapper（cluster-admin 视角）。
 * 主要操作 jar_bytes / status / failure_detail 字段。
 */
public interface ProtocolJarRegistryMapper extends BaseMapper<ProtocolJarRegistry> {

    @Select("SELECT * FROM protocol_jar_registry ORDER BY id ASC")
    List<ProtocolJarRegistry> selectAll();

    @Select("SELECT * FROM protocol_jar_registry WHERE name = #{name} LIMIT 1")
    ProtocolJarRegistry selectByName(@Param("name") String name);

    /** upsert jar_bytes + file_name + status=INIT */
    @Update("UPDATE protocol_jar_registry SET jar_bytes = #{jarBytes}, status = 'INIT', failure_detail = NULL, updated_at = #{updatedAt} WHERE name = #{name}")
    int updateJarBytes(@Param("name") String name,
                       @Param("jarBytes") byte[] jarBytes,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 更新状态 + 失败明细 */
    @Update("UPDATE protocol_jar_registry SET status = #{status}, failure_detail = #{failureDetail}, updated_at = #{updatedAt} WHERE name = #{name}")
    int updateStatus(@Param("name") String name,
                     @Param("status") String status,
                     @Param("failureDetail") String failureDetail,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT EXISTS(SELECT 1 FROM protocol_jar_registry WHERE name = #{name})")
    boolean existsByName(@Param("name") String name);
}
