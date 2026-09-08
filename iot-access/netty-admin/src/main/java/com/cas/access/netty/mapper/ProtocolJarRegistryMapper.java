package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.cluster.node.entity.ProtocolJarRegistry;
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
            "provider_class=#{providerClass}, status=#{status}, " +
            "loaded_at=#{loadedAt}, updated_at=#{updatedAt} " +
            "WHERE id=#{id}")
    int updateFull(ProtocolJarRegistry entity);

    @Update("UPDATE protocol_jar_registry " +
            "SET status=#{status}, updated_at=#{updatedAt} " +
            "WHERE name=#{name}")
    int updateStatusByName(ProtocolJarRegistry entity);

    @Update("UPDATE protocol_jar_registry " +
            "SET provider_class=null, updated_at=#{updatedAt} " +
            "WHERE name=#{name}")
    int clearProvider(@Param("name") String name, @Param("updatedAt") LocalDateTime updatedAt);

}
