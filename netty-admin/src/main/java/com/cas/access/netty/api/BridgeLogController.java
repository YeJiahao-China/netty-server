package com.cas.access.netty.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cas.access.netty.entity.BridgeLog;
import com.cas.access.netty.entity.PortTopicBinding;
import com.cas.access.netty.protocol.MessageBridge;
import com.cas.access.netty.service.BridgeLogService;
import com.cas.access.netty.service.PortTopicService;
import com.cas.access.netty.util.DateUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据桥接日志管理 HTTP 接口（端口 2310）。
 * <p>
 * 提供桥接日志的分页查询、详情查看、删除和统计能力。
 *
 * @author yjh_c
 */
@Slf4j
@RestController
@RequestMapping("/bridge-logs")
public class BridgeLogController {

    @Resource
    private BridgeLogService bridgeLogService;

    @Resource
    private MessageBridge messageBridge;

    @Resource
    private PortTopicService portTopicService;
    /**
     * 分页查询桥接日志，支持按端口和成功状态过滤。
     *
     * @param page       页码（默认 1）
     * @param size       每页条数（默认 20）
     * @param serverPort 服务端端口（可选）
     * @param success    成功状态（可选）
     */
    @GetMapping
    public Map<String, Object> page(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "serverPort", required = false) Integer serverPort,
            @RequestParam(value = "success", required = false) Boolean success) {

        Page<BridgeLog> result = bridgeLogService.page(page, size, serverPort, success);

        List<Map<String, Object>> records = new ArrayList<>();
        for (BridgeLog b : result.getRecords()) {
            records.add(toMap(b));
        }

        Map<String, Object> resp = ok();
        resp.put("data", records);
        resp.put("total", result.getTotal());
        resp.put("pages", result.getPages());
        resp.put("current", result.getCurrent());
        resp.put("size", result.getSize());
        return resp;
    }

    /**
     * 查询单条日志详情。
     */
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        BridgeLog b = bridgeLogService.getById(id);
        if (b == null) {
            return fail("日志不存在: " + id);
        }
        Map<String, Object> resp = ok();
        resp.put("data", toMap(b));
        return resp;
    }

    /**
     * 统计信息（总数、成功数、失败数、成功率）。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long total = bridgeLogService.count();
        long successCount = bridgeLogService.countBySuccess(true);
        long failureCount = bridgeLogService.countBySuccess(false);
        double rate = total > 0 ? (double) successCount / total * 100 : 0;

        Map<String, Object> resp = ok();
        resp.put("total", total);
        resp.put("successCount", successCount);
        resp.put("failureCount", failureCount);
        resp.put("successRate", String.format("%.1f", rate));
        return resp;
    }

    /**
     * 按 ID 删除单条日志。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        boolean ok = bridgeLogService.deleteById(id);
        if (!ok) {
            return fail("删除失败，日志不存在: " + id);
        }
        log.info("桥接日志已删除: id={}", id);
        return ok();
    }

    /**
     * 批量删除日志。
     *
     * @param ids 逗号分隔的 ID 列表
     */
    @DeleteMapping("/batch")
    public Map<String, Object> deleteBatch(@RequestParam("ids") String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            return fail("ID 列表不能为空");
        }
        List<Long> idList = new ArrayList<>();
        for (String s : ids.split(",")) {
            try {
                idList.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (idList.isEmpty()) {
            return fail("无有效 ID");
        }
        int deleted = bridgeLogService.deleteBatch(idList);
        log.info("批量删除桥接日志: {} 条", deleted);
        Map<String, Object> resp = ok();
        resp.put("deleted", deleted);
        return resp;
    }

    /**
     * 清空所有桥接日志。
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clear() {
        long before = bridgeLogService.count();
        bridgeLogService.clearAll();
        log.info("已清空所有桥接日志: {} 条", before);
        Map<String, Object> resp = ok();
        resp.put("deleted", before);
        return resp;
    }

    /**
     * 重入队：重新发送日志中的原始数据到 RocketMQ。
     * <p>
     * 发送成功后物理删除该日志；发送失败则保留原日志。
     */
    @PostMapping("/{id}/resend")
    public Map<String, Object> resend(@PathVariable Long id) {
        BridgeLog logEntity = bridgeLogService.getById(id);
        if (logEntity == null) {
            return fail("日志不存在: " + id);
        }

        // 校验必要字段
        if (logEntity.getRawData() == null || logEntity.getRawData().isEmpty()) {
            return fail("原始报文为空，无法重入队");
        }
        PortTopicBinding portTopicBinding = portTopicService.selectByPort(logEntity.getServerPort());
        String topicName = portTopicBinding.getTopicName();
        if (topicName == null || topicName.isEmpty()) {
            return fail("Topic 为空，无法重入队");
        }

        // 监听端口对应的topic已更新，立即更新桥接日志信息
        if (!topicName.equals(logEntity.getTopicName())) {
            logEntity.setTopicName(topicName);
            bridgeLogService.updateById(logEntity);
        }

        log.info("收到重入队请求: id={}, server={}:{}, client={}:{}, topic={}",
                id, logEntity.getServerIp(), logEntity.getServerPort(),
                logEntity.getClientIp(), logEntity.getClientPort(), topicName);


        boolean success = messageBridge.resend(
                logEntity.getServerPort(),
                logEntity.getServerIp(),
                logEntity.getClientPort(),
                logEntity.getClientIp(),
                topicName,
                logEntity.getRawData()
        );

        if (success) {
            bridgeLogService.deleteById(id);
            log.info("重入队成功并已删除原日志: id={}", id);
            Map<String, Object> resp = ok();
            resp.put("message", "重入队成功，日志已删除");
            return resp;
        } else {
            log.warn("重入队失败，保留原日志: id={}", id);
            return fail("重入队失败，日志已保留，请检查服务状态或稍后重试");
        }
    }

    /* ===================== 私有方法 ===================== */

    private Map<String, Object> toMap(BridgeLog b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("serverPort", b.getServerPort());
        m.put("serverIp", b.getServerIp());
        m.put("clientPort", b.getClientPort());
        m.put("clientIp", b.getClientIp());
        m.put("topicName", b.getTopicName());
        m.put("success", b.getSuccess());
        m.put("retryCount", b.getRetryCount());
        m.put("costMs", b.getCostMs());
        m.put("rawData", b.getRawData());
        m.put("errorMsg", b.getErrorMsg());
        m.put("createdAt", b.getCreatedAt() == null ? "" : DateUtils.format(java.sql.Timestamp.valueOf(b.getCreatedAt())));
        return m;
    }

    private Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        return m;
    }

    private Map<String, Object> fail(String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }
}
