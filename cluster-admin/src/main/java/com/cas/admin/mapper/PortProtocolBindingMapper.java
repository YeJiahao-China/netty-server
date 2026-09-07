package com.cas.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.admin.entity.PortProtocolBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 端口-协议绑定 Mapper（cluster-admin 直接查同库）。
 */
@Mapper
public interface PortProtocolBindingMapper extends BaseMapper<PortProtocolBinding> {

    @Select("SELECT * FROM port_protocol_binding ORDER BY port ASC")
    List<PortProtocolBinding> selectAll();

    @Select("SELECT * FROM port_protocol_binding WHERE protocol_name = #{name}")
    List<PortProtocolBinding> selectByName(@Param("name") String name);
}
