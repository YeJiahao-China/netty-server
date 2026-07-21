package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.ProtocolJarRegistry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
    ProtocolJarRegistry selectByNameIgnoreDeleted(@Param("name") String name);

    @Select("SELECT * FROM protocol_jar_registry ORDER BY loaded_at DESC")
    List<ProtocolJarRegistry> selectAllIncludeDeleted();

    // 自定义更新（完全忽略逻辑删除）
    @Update("UPDATE protocol_jar_registry " +
            "SET version=#{version}, description=#{description}, source=#{source}, " +
            "jar_path=#{jarPath}, provider_class=#{providerClass}, active=#{active}, deleted=#{deleted}," +
            "loaded_at=#{loadedAt}, updated_at=#{updatedAt} " +
            "WHERE id=#{id}")
    int updateIgnoreLogic(ProtocolJarRegistry entity);

}
