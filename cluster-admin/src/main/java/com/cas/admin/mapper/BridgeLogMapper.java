package com.cas.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.admin.entity.BridgeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 数据桥接日志 Mapper（cluster-admin 直接查同库）。
 */
@Mapper
public interface BridgeLogMapper extends BaseMapper<BridgeLog> {

    /**
     * 单次聚合查询总数/成功数/失败数。
     */
    @Select("SELECT COUNT(*) AS total, " +
            "COUNT(*) FILTER (WHERE success = true) AS \"successCount\", " +
            "COUNT(*) FILTER (WHERE success = false) AS \"failureCount\" " +
            "FROM bridge_log")
    Map<String, Object> selectStats();
}
