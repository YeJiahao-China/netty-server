package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.PortBinding;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 端口-协议绑定 Mapper（继承 BaseMapper 即获 CRUD）。
 *
 * @author yjh_c
 */
public interface PortBindingMapper extends BaseMapper<PortBinding> {
    @Select("SELECT * FROM port_protocol_binding WHERE protocol_name = #{name} LIMIT 1")
    PortBinding selectByNameIgnoreDeleted(String name);


    @Update("UPDATE port_protocol_binding " +
            "SET protocol_name = #{protocolName}, " +
            "enabled = #{enabled}, " +
            "deleted = #{deleted}, " +
            "updated_at = #{updatedAt} " +
            "WHERE port = #{port}")
    int updateByPortIgnoreLogic(@Param("port") Integer port,
                                @Param("protocolName") String protocolName,
                                @Param("enabled") Boolean enabled,
                                @Param("deleted") Integer deleted,
                                @Param("updatedAt") LocalDateTime updatedAt);
}
