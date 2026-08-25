package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.BridgeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;

import java.util.Map;

/**
 * 数据桥接日志 Mapper。
 */
@Mapper
public interface BridgeLogMapper extends BaseMapper<BridgeLog> {

    /**
     * 使用 MyBatis Cursor 流式查询全部失败日志。
     * <p>
     * PostgreSQL 驱动在 fetchSize > 0 + autoCommit=false 时启用 server-side cursor，
     * 不会将全量数据加载到内存，而是按 fetchSize 批量从数据库拉取。
     * 必须在事务内消费（{@code @Transactional}）。
     *
     * @return 按 id 升序的失败日志游标
     */
    @Select("SELECT * FROM bridge_log WHERE success = false ORDER BY id ASC")
    @Options(fetchSize = 500)
    Cursor<BridgeLog> selectFailedCursor();

    /**
     * 单次聚合查询总数/成功数/失败数。
     * <p>
     * 三项计数在同一 SQL 快照内完成，避免并发插入导致 total ≠ success + failure。
     *
     * @return key 分别为 total / successCount / failureCount
     */
    @Select("SELECT COUNT(*) AS total, " +
            "COUNT(*) FILTER (WHERE success = true) AS \"successCount\", " +
            "COUNT(*) FILTER (WHERE success = false) AS \"failureCount\" " +
            "FROM bridge_log")
    Map<String, Object> selectStats();
}
