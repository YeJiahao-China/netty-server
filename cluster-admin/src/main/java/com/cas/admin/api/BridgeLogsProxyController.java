package com.cas.admin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cas.admin.common.ApiResponse;
import com.cas.admin.common.ConvertUtil;
import com.cas.admin.entity.BridgeLog;
import com.cas.admin.mapper.BridgeLogMapper;
import com.cas.admin.service.PrimaryNodeRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据桥接日志 API：在管理中心直接查同库（不依赖 iot-access 在线）。
 * <p>
 * 查询/删除类操作本地直查 DB；重投递需要 RocketMQ producer，
 * 由 cluster-admin 同步调用一台在线 iot-access 执行：
 * <ul>
 *   <li>单个重投递：同步阻塞等待 access 返回，前端拿到成功/失败结果</li>
 *   <li>一键重投递：access 内部虚拟线程池异步执行，HTTP 立即返回提交条数</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/bridge-logs")
@RequiredArgsConstructor
public class BridgeLogsProxyController {

    private final BridgeLogMapper bridgeLogMapper;
    private final PrimaryNodeRouter router;
    private final RestClient.Builder nodeRestClientBuilder;
    private final ObjectMapper objectMapper;

    /* ========== 查询类：直查同库 ========== */

    @GetMapping
    public Map<String, Object> page(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "serverPort", required = false) Integer serverPort,
            @RequestParam(value = "success", required = false) Boolean success) {

        LambdaQueryWrapper<BridgeLog> wrapper = new LambdaQueryWrapper<BridgeLog>()
                .orderByDesc(BridgeLog::getCreatedAt);
        if (serverPort != null) wrapper.eq(BridgeLog::getServerPort, serverPort);
        if (success != null) wrapper.eq(BridgeLog::getSuccess, success);

        Page<BridgeLog> result = bridgeLogMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (BridgeLog b : result.getRecords()) records.add(toMap(b));

        Map<String, Object> resp = ApiResponse.ok();
        resp.put("data", records);
        resp.put("total", result.getTotal());
        resp.put("pages", result.getPages());
        resp.put("current", result.getCurrent());
        resp.put("size", result.getSize());
        return resp;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        BridgeLog b = bridgeLogMapper.selectById(id);
        if (b == null) return ApiResponse.fail("日志不存在: " + id);
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("data", toMap(b));
        return resp;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = bridgeLogMapper.selectStats();
        long total = ConvertUtil.toLong(s.get("total"));
        long successCount = ConvertUtil.toLong(s.get("successCount"));
        long failureCount = ConvertUtil.toLong(s.get("failureCount"));
        double rate = total > 0 ? (double) successCount / total * 100 : 0;

        Map<String, Object> resp = ApiResponse.ok();
        resp.put("total", total);
        resp.put("successCount", successCount);
        resp.put("failureCount", failureCount);
        resp.put("successRate", String.format("%.1f", rate));
        return resp;
    }

    /* ========== 删除类：直查同库 ========== */

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        int rows = bridgeLogMapper.deleteById(id);
        if (rows <= 0) return ApiResponse.fail("删除失败，日志不存在: " + id);
        log.info("桥接日志已删除: id={}", id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/batch")
    public Map<String, Object> deleteBatch(@RequestParam("ids") String ids) {
        if (ids == null || ids.trim().isEmpty()) return ApiResponse.fail("ID 列表不能为空");
        List<Long> idList = new ArrayList<>();
        for (String s : ids.split(",")) {
            try { idList.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
        }
        if (idList.isEmpty()) return ApiResponse.fail("无有效 ID");
        int deleted = bridgeLogMapper.deleteBatchIds(idList);
        log.info("批量删除桥接日志: {} 条", deleted);
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("deleted", deleted);
        return resp;
    }

    @DeleteMapping("/clear")
    public Map<String, Object> clear() {
        long before = bridgeLogMapper.selectCount(new LambdaQueryWrapper<>());
        bridgeLogMapper.delete(new LambdaQueryWrapper<>());
        log.info("已清空所有桥接日志: {} 条", before);
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("deleted", before);
        return resp;
    }

    /* ========== 重投递类：选一台在线 iot-access 同步调用 ========== */

    /**
     * 单个重投递：同步阻塞等待 iot-access 返回结果，前端拿到成功/失败。
     */
    @PostMapping("/{id}/resend")
    public Map<String, Object> resend(@PathVariable Long id) {
        String base = router.resolveAccessBase();
        if (base == null) return ApiResponse.fail("没有可用的 iot-access 节点");
        String url = base + "/bridge-logs/" + id + "/resend";
        try {
            String body = nodeRestClientBuilder.build()
                    .post().uri(url)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            result.put("iot-access", base);
            log.info("重投递调用完成: 日志id={}, iot-access={}, 调用详情={}", id, base, result.get("reason"));
            return result;
        } catch (Exception e) {
            log.warn("重投递调用失败: id={}, iot-access={}", id, base, e);
            Map<String, Object> r = ApiResponse.fail("重投递调用失败: 请检查MQ服务健康状态");
            r.put("iot-access", base);
            return r;
        }
    }

    /**
     * 一键重投递：iot-access 内部虚拟线程池异步执行，HTTP 立即返回提交条数。
     */
    @PostMapping("/batch-resend")
    public Map<String, Object> batchResend(@RequestParam(value = "batchSize", defaultValue = "5000") int batchSize) {
        String base = router.resolveAccessBase();
        if (base == null) return ApiResponse.fail("没有可用的 iot-access 节点");
        String url = base + "/bridge-logs/batch-resend?batchSize=" + batchSize;
        try {
            String body = nodeRestClientBuilder.build()
                    .post().uri(url)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            log.info("一键重投递已提交: iot-access={}, submitted={}", base, result.get("submitted"));
            return result;
        } catch (Exception e) {
            log.warn("一键重投递调用失败: iot-access={}, err={}", base, e.getMessage());
            Map<String, Object> r = ApiResponse.fail("iot-access 调用失败: " + e.getMessage());
            r.put("iot-access", base);
            return r;
        }
    }

    /* ========== 私有方法 ========== */

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
        m.put("createdAt", b.getCreatedAt() == null ? "" : b.getCreatedAt().format(ApiResponse.DT_FMT));
        return m;
    }
}
