package com.cas.access.netty.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.PortTopicBinding;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;


public interface PortTopicMapper extends BaseMapper<PortTopicBinding> {

    @Select("SELECT * FROM port_topic_binding WHERE port = #{port} LIMIT 1")
    PortTopicBinding selectByPort(int port);

    @Select("SELECT * FROM port_topic_binding ORDER BY port ASC")
    List<PortTopicBinding> selectAll();

    /**
     * 查询可用端口：在 port_protocol_binding 中 enabled=true，
     * 且在 port_topic_binding 中不存在的 port。
     */
    @Select("SELECT ppb.port FROM port_protocol_binding ppb " +
            "WHERE ppb.enabled = TRUE " +
            "AND ppb.port NOT IN (SELECT ptb.port FROM port_topic_binding ptb) " +
            "ORDER BY ppb.port ASC")
    List<Integer> selectAvailablePorts();

    @Update("UPDATE port_topic_binding " +
            "SET topic_name = #{topicName}, " +
            "enabled = #{enabled}, " +
            "updated_at = #{updatedAt} " +
            "WHERE port = #{port}")
    int updateByPort(@Param("port") Integer port,
                     @Param("topicName") String topicName,
                     @Param("enabled") Boolean enabled,
                     @Param("updatedAt") LocalDateTime updatedAt);

}
