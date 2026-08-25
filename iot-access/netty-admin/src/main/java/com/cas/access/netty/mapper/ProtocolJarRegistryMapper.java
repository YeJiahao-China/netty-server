package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.ProtocolJarRegistry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 协议 jar 注册表 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即可获得基本 CRUD 能力，无需 XML。
 *
 * @author yjh_c
 */
public interface ProtocolJarRegistryMapper extends BaseMapper<ProtocolJarRegistry> {

    @Select("SELECT * FROM protocol_jar_registry WHERE name = #{name} LIMIT 1")
    ProtocolJarRegistry selectByName(@Param("name") String name);

    @Select("SELECT * FROM protocol_jar_registry ORDER BY loaded_at DESC")
    List<ProtocolJarRegistry> selectAll();

    @Update("UPDATE protocol_jar_registry " +
            "SET version=#{version}, description=#{description}, source=#{source}, " +
            "jar_path=#{jarPath}, provider_class=#{providerClass}, active=#{active}, " +
            "loaded_at=#{loadedAt}, updated_at=#{updatedAt} " +
            "WHERE id=#{id}")
    int updateFull(ProtocolJarRegistry entity);

    @Update("UPDATE protocol_jar_registry " +
            "SET active=#{active}, updated_at=#{updatedAt} " +
            "WHERE name=#{name}")
    int updateActiveByName(ProtocolJarRegistry entity);

    @Update("UPDATE protocol_jar_registry " +
            "SET jar_path=null, provider_class=null, updated_at=#{updatedAt} " +
            "WHERE name=#{name}")
    int clearJarPathAndProvider(@Param("name") String name,@Param("updatedAt") LocalDateTime updatedAt);

}
