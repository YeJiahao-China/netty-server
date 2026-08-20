package com.cas.access.netty.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cas.access.netty.entity.BridgeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;

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
}
