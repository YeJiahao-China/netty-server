package com.cas.access.netty.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cas.access.netty.entity.BridgeLog;
import com.cas.access.netty.mapper.BridgeLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据桥接日志 Service。
 * 提供日志持久化和查询能力。
 */
@Slf4j
@Service
public class BridgeLogService {

    @Resource
    private BridgeLogMapper bridgeLogMapper;

    /**
     * 保存一条桥接日志。
     * 该方法应在业务线程池中调用，避免阻塞 Netty IO 线程。
     */
    public void save(BridgeLog bridgeLog) {
        try {
            if (bridgeLog.getCreatedAt() == null) {
                bridgeLog.setCreatedAt(LocalDateTime.now());
            }
            bridgeLogMapper.insert(bridgeLog);
        } catch (Exception e) {
            log.warn("持久化桥接日志失败: serverPort={}, success={}, error={}",
                    bridgeLog.getServerPort(), bridgeLog.getSuccess(), e.getMessage());
        }
    }

    /**
     * 按端口查询最近的日志（倒序）。
     */
    public List<BridgeLog> listByPort(int port, int limit) {
        return bridgeLogMapper.selectList(
                new LambdaQueryWrapper<BridgeLog>()
                        .eq(BridgeLog::getServerPort, port)
                        .orderByDesc(BridgeLog::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 分页查询所有日志（倒序），支持按端口和成功状态过滤。
     */
    public Page<BridgeLog> page(int current, int size, Integer serverPort, Boolean success) {
        LambdaQueryWrapper<BridgeLog> wrapper = new LambdaQueryWrapper<BridgeLog>()
                .orderByDesc(BridgeLog::getCreatedAt);
        if (serverPort != null) {
            wrapper.eq(BridgeLog::getServerPort, serverPort);
        }
        if (success != null) {
            wrapper.eq(BridgeLog::getSuccess, success);
        }
        return bridgeLogMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 按 ID 查询单条日志。
     */
    public BridgeLog getById(Long id) {
        return bridgeLogMapper.selectById(id);
    }

    /**
     * 按 ID 删除单条日志。
     */
    public boolean deleteById(Long id) {
        return bridgeLogMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除日志。
     */
    public int deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return bridgeLogMapper.deleteBatchIds(ids);
    }

    /**
     * 清空所有桥接日志。
     */
    public void clearAll() {
        bridgeLogMapper.delete(new LambdaQueryWrapper<BridgeLog>());
    }

    /**
     * 统计总数。
     */
    public long count() {
        return bridgeLogMapper.selectCount(new LambdaQueryWrapper<>());
    }

    /**
     * 按成功/失败统计数量。
     */
    public long countBySuccess(boolean success) {
        return bridgeLogMapper.selectCount(
                new LambdaQueryWrapper<BridgeLog>().eq(BridgeLog::getSuccess, success)
        );
    }

    public void updateById(BridgeLog logEntity) {
        bridgeLogMapper.updateById(logEntity);
    }
}
