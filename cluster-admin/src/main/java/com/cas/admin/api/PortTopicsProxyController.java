package com.cas.admin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.admin.common.ApiResponse;
import com.cas.admin.entity.PortTopicBinding;
import com.cas.admin.mapper.PortTopicMapper;
import com.cas.admin.mq.RocketMQTopicManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端口-主题桥接 API：全部直查同库 + 直接创建 RocketMQ topic，不依赖 iot-access 在线。
 */
@Slf4j
@RestController
@RequestMapping("/port-topics")
@RequiredArgsConstructor
public class PortTopicsProxyController {

    private final PortTopicMapper portTopicMapper;
    private final RocketMQTopicManager rocketMQTopicManager;

    /* ========== 查询类：直查同库 ========== */

    @GetMapping
    public Map<String, Object> list() {
        List<PortTopicBinding> bindings = portTopicMapper.selectAll();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PortTopicBinding b : bindings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", b.getPort());
            m.put("topicName", b.getTopicName());
            m.put("enabled", b.getEnabled());
            m.put("createdAt", b.getCreatedAt() == null ? "" : b.getCreatedAt().format(ApiResponse.DT_FMT));
            m.put("updatedAt", b.getUpdatedAt() == null ? "" : b.getUpdatedAt().format(ApiResponse.DT_FMT));
            list.add(m);
        }
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("data", list);
        return resp;
    }

    @GetMapping("/available-ports")
    public Map<String, Object> availablePorts() {
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("ports", portTopicMapper.selectAvailablePorts());
        return resp;
    }

    @GetMapping("/{port}")
    public Map<String, Object> getByPort(@PathVariable int port) {
        PortTopicBinding b = portTopicMapper.selectByPort(port);
        if (b == null) return ApiResponse.fail("端口 " + port + " 未绑定主题");
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("port", b.getPort());
        resp.put("topicName", b.getTopicName());
        resp.put("enabled", b.getEnabled());
        return resp;
    }

    /* ========== 启停/解绑/删除：直查同库 ========== */

    @PutMapping("/{port}/enabled")
    public Map<String, Object> toggleEnabled(@PathVariable int port,
                                             @RequestParam("enabled") boolean enabled) {
        PortTopicBinding existing = portTopicMapper.selectByPort(port);
        if (existing == null) return ApiResponse.fail("端口 " + port + " 未绑定主题");
        int rows = portTopicMapper.update(null,
                new LambdaUpdateWrapper<PortTopicBinding>()
                        .eq(PortTopicBinding::getPort, port)
                        .set(PortTopicBinding::getEnabled, enabled)
                        .set(PortTopicBinding::getUpdatedAt, LocalDateTime.now()));
        if (rows <= 0) return ApiResponse.fail("操作失败");
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("port", port);
        resp.put("enabled", enabled);
        return resp;
    }

    @DeleteMapping("/{port}")
    public Map<String, Object> unbind(@PathVariable int port) {
        PortTopicBinding existing = portTopicMapper.selectByPort(port);
        if (existing == null) return ApiResponse.fail("端口 " + port + " 未绑定主题");
        portTopicMapper.update(null,
                new LambdaUpdateWrapper<PortTopicBinding>()
                        .eq(PortTopicBinding::getPort, port)
                        .set(PortTopicBinding::getEnabled, false)
                        .set(PortTopicBinding::getUpdatedAt, LocalDateTime.now()));
        log.info("端口[{}]已解绑主题[{}]", port, existing.getTopicName());
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("port", port);
        resp.put("topicName", existing.getTopicName());
        return resp;
    }

    @DeleteMapping("/{port}/force")
    public Map<String, Object> forceDelete(@PathVariable int port) {
        int rows = portTopicMapper.delete(
                new LambdaQueryWrapper<PortTopicBinding>()
                        .eq(PortTopicBinding::getPort, port));
        if (rows <= 0) return ApiResponse.fail("端口 " + port + " 不存在或删除失败");
        log.info("端口[{}]主题绑定已物理删除", port);
        Map<String, Object> resp = ApiResponse.ok();
        resp.put("port", port);
        return resp;
    }

    /* ========== 新增/更新：直接执行（DB + RocketMQ topic） ========== */

    @PostMapping
    public Map<String, Object> bind(@RequestParam("port") int port,
                                    @RequestParam("topicName") String topicName) {
        if (port < 1024 || port > 65535) {
            return ApiResponse.fail("端口范围必须在 1024-65535");
        }
        if (topicName == null || topicName.trim().isEmpty()) {
            return ApiResponse.fail("topic名称不能为空");
        }
        PortTopicBinding existing = portTopicMapper.selectByPort(port);
        if (existing != null) {
            return ApiResponse.fail("端口 " + port + " 已绑定主题[" + existing.getTopicName() + "]，请先解绑或使用更新接口");
        }
        try {
            rocketMQTopicManager.createTopicIfNotExist(topicName.trim());
            PortTopicBinding entity = new PortTopicBinding();
            entity.setPort(port);
            entity.setTopicName(topicName.trim());
            entity.setEnabled(Boolean.TRUE);
            portTopicMapper.insert(entity);
            log.info("端口[{}]绑定主题[{}]", port, topicName);
            Map<String, Object> resp = ApiResponse.ok();
            resp.put("port", port);
            resp.put("topicName", topicName);
            return resp;
        } catch (Exception e) {
            Map<String, Object> resp = ApiResponse.fail("新增数据桥接配置失败, 请检查MQ健康状态");
            resp.put("port", port);
            resp.put("topicName", topicName);
            return resp;
        }

    }

    @PutMapping("/{port}")
    public Map<String, Object> updateTopic(@PathVariable int port,
                                           @RequestParam("topicName") String topicName) {
        if (topicName == null || topicName.trim().isEmpty()) {
            return ApiResponse.fail("topic名称不能为空");
        }
        PortTopicBinding existing = portTopicMapper.selectByPort(port);
        if (existing == null) {
            return ApiResponse.fail("端口 " + port + " 未绑定主题，请先新增");
        }
        try {
            rocketMQTopicManager.createTopicIfNotExist(topicName.trim());
            int rows = portTopicMapper.update(null,
                    new LambdaUpdateWrapper<PortTopicBinding>()
                            .eq(PortTopicBinding::getPort, port)
                            .set(PortTopicBinding::getTopicName, topicName.trim())
                            .set(PortTopicBinding::getEnabled, true)
                            .set(PortTopicBinding::getUpdatedAt, LocalDateTime.now()));
            if (rows <= 0) {
                return ApiResponse.fail("更新失败");
            }

            log.info("端口[{}]更新主题为[{}]", port, topicName);
            Map<String, Object> resp = ApiResponse.ok();
            resp.put("port", port);
            resp.put("topicName", topicName);
            return resp;
        } catch (Exception e) {
            Map<String, Object> resp = ApiResponse.fail("更新数据桥接配置失败, 请检查MQ健康状态");
            resp.put("port", port);
            resp.put("topicName", topicName);
            return resp;
        }

    }
}
