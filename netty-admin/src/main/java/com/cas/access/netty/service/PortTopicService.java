package com.cas.access.netty.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.access.netty.entity.PortTopicBinding;
import com.cas.access.netty.mapper.PortTopicMapper;
import com.cas.access.netty.protocol.PortTopicSync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;


@Slf4j
@Service
public class PortTopicService implements PortTopicSync {

    @Resource
    PortTopicMapper portTopicMapper;

    @Override
    public void upsertPortTopicBind(int port, String topicName) {
        try {
            PortTopicBinding existing = portTopicMapper.selectByPort(port);
            if (existing == null) {
                PortTopicBinding entity = new PortTopicBinding();
                entity.setPort(port);
                entity.setTopicName(topicName);
                entity.setEnabled(Boolean.TRUE);
                portTopicMapper.insert(entity);
            } else {
                portTopicMapper.updateByPort(port, topicName, true, LocalDateTime.now());
            }
            log.debug("持久化数据桥接: {} → {}", port, topicName);
        } catch (Exception e) {
            log.warn("持久化数据桥接异常: {} → {}", port, topicName, e);
        }
    }

    @Override
    public void persistUnbind(int port) {
        PortTopicBinding existing = portTopicMapper.selectByPort(port);
        try {
            portTopicMapper.update(null,
                    new LambdaUpdateWrapper<PortTopicBinding>()
                            .eq(PortTopicBinding::getPort, port)
                            .set(PortTopicBinding::getEnabled, Boolean.FALSE)
                            .set(PortTopicBinding::getUpdatedAt, LocalDateTime.now()));
            log.debug("解绑数据桥接: port={}，topic={}", port, existing == null ? "" : existing.getTopicName());
        } catch (Exception e) {
            log.warn("解绑数据桥接失败: port={},topic={}", port, existing == null ? "" : existing.getTopicName(), e);
        }

    }

    /**
     * 查询所有端口-主题绑定记录。
     */
    public List<PortTopicBinding> listAll() {
        return portTopicMapper.selectAll();
    }

    /**
     * 查询可用端口：在 port_protocol_binding 中 enabled=true，
     * 且在 port_topic_binding 中不存在的 port。
     */
    public List<Integer> selectAvailablePorts() {
        return portTopicMapper.selectAvailablePorts();
    }

    /**
     * 按端口号查询绑定记录。
     */
    public PortTopicBinding selectByPort(int port) {
        return portTopicMapper.selectByPort(port);
    }

    /**
     * 按端口号查询绑定记录。
     */
    public PortTopicBinding selectAvailableByPort(int port) {
        return portTopicMapper.selectAvailableByPort(port);
    }

    /**
     * 按端口号物理删除绑定记录。
     */
    public boolean deleteByPort(int port) {
        PortTopicBinding portTopicBinding = portTopicMapper.selectByPort(port);
        String topicName = portTopicBinding.getTopicName();
        try {
            int rows = portTopicMapper.delete(
                    new LambdaQueryWrapper<PortTopicBinding>()
                            .eq(PortTopicBinding::getPort, port));
            log.info("删除数据桥接绑定: port={}, topicName={}", port, topicName);
            return rows > 0;
        } catch (Exception e) {
            log.warn("删除数据桥接失败: port={}, topicName={}", port,topicName, e);
            return false;
        }
    }

    /**
     * 启用/禁用绑定。
     */
    public boolean updateEnabled(int port, boolean enabled) {
        PortTopicBinding portTopicBinding = portTopicMapper.selectByPort(port);
        String topicName = portTopicBinding.getTopicName();
        try {
            int rows = portTopicMapper.update(null,
                    new LambdaUpdateWrapper<PortTopicBinding>()
                            .eq(PortTopicBinding::getPort, port)
                            .set(PortTopicBinding::getEnabled, enabled)
                            .set(PortTopicBinding::getUpdatedAt, LocalDateTime.now()));
            log.info("更新数据桥接状态: port={},topicName={}, enabled={}", port, topicName,enabled);
            return rows > 0;
        } catch (Exception e) {
            log.warn("更新数据桥接状态失败: port={},topicName={}", port,topicName, e);
            return false;
        }
    }

    /**
     * 更新端口的 topic 名称。
     */
    public boolean updateTopicName(int port, String topicName) {
        try {
            int rows = portTopicMapper.updateByPort(port, topicName, true, LocalDateTime.now());
            log.info("更新数据桥接: port={}, topic={}", port, topicName);
            return rows > 0;
        } catch (Exception e) {
            log.warn("更新数据桥接失败: port={}", port, e);
            return false;
        }
    }
}
