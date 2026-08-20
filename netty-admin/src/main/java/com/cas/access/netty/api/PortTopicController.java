package com.cas.access.netty.api;

import com.cas.access.netty.entity.PortTopicBinding;
import com.cas.access.netty.mq.RocketMQTopicManager;
import com.cas.access.netty.service.PortTopicService;
import com.cas.access.netty.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端口-主题桥接管理 HTTP 接口（端口 2310）。
 *
 * <p>用于管理 TCP 监听端口与 RocketMQ LiteTopic 的绑定关系。
 * ReadEventHandler 在收到设备报文后，根据端口查找对应 topic 并桥接转发。
 *
 * @author yjh_c
 */
@Slf4j
@RestController
@RequestMapping("/port-topics")
public class PortTopicController {

    @Resource
    private PortTopicService portTopicService;

    @Resource
    private RocketMQTopicManager rocketMQTopicManager;

    /**
     * 查询所有端口-主题绑定。
     */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> resp = ok();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PortTopicBinding b : portTopicService.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", b.getPort());
            m.put("topicName", b.getTopicName());
            m.put("enabled", b.getEnabled());
            m.put("createdAt", b.getCreatedAt() == null ? "" : DateUtils.format(java.sql.Timestamp.valueOf(b.getCreatedAt())));
            m.put("updatedAt", b.getUpdatedAt() == null ? "" : DateUtils.format(java.sql.Timestamp.valueOf(b.getUpdatedAt())));
            list.add(m);
        }
        resp.put("data", list);
        return resp;
    }

    /**
     * 查询可用端口（已启用协议绑定且未绑定 topic 的端口）。
     */
    @GetMapping("/available-ports")
    public Map<String, Object> availablePorts() {
        Map<String, Object> resp = ok();
        resp.put("ports", portTopicService.selectAvailablePorts());
        return resp;
    }

    /**
     * 按端口号查询绑定详情。
     */
    @GetMapping("/{port}")
    public Map<String, Object> getByPort(@PathVariable int port) {
        PortTopicBinding b = portTopicService.selectByPort(port);
        if (b == null) {
            return fail("端口 " + port + " 未绑定主题");
        }
        Map<String, Object> resp = ok();
        resp.put("port", b.getPort());
        resp.put("topicName", b.getTopicName());
        resp.put("enabled", b.getEnabled());
        return resp;
    }

    /**
     * 新增端口-主题绑定。
     *
     * @param port      TCP 监听端口
     * @param topicName RocketMQ LiteTopic 名称
     */
    @PostMapping
    public Map<String, Object> bind(@RequestParam("port") int port,
                                    @RequestParam("topicName") String topicName) {
        if (port < 1024 || port > 65535) {
            return fail("端口范围必须在 1024-65535");
        }
        if (topicName == null || topicName.trim().isEmpty()) {
            return fail("topic名称不能为空");
        }
        PortTopicBinding existing = portTopicService.selectByPort(port);
        if (existing != null) {
            return fail("端口 " + port + " 已绑定主题[" + existing.getTopicName() + "]，请先解绑或使用更新接口");
        }
        portTopicService.upsertPortTopicBind(port, topicName.trim());
        // 在RocketMQ中创建Topic
        rocketMQTopicManager.createTopicIfNotExist(topicName.trim());
        log.info("端口[{}]绑定主题[{}]", port, topicName);
        Map<String, Object> resp = ok();
        resp.put("port", port);
        resp.put("topicName", topicName);
        return resp;
    }

    /**
     * 更新端口的 topic 名称。
     */
    @PutMapping("/{port}")
    public Map<String, Object> updateTopic(@PathVariable int port,
                                           @RequestParam("topicName") String topicName) {
        if (topicName == null || topicName.trim().isEmpty()) {
            return fail("topic名称不能为空");
        }
        PortTopicBinding existing = portTopicService.selectByPort(port);
        if (existing == null) {
            return fail("端口 " + port + " 未绑定主题，请先新增");
        }
        boolean ok = portTopicService.updateTopicName(port, topicName.trim());
        // 在RocketMQ中创建Topic
        rocketMQTopicManager.createTopicIfNotExist(topicName.trim());
        if (!ok) {
            return fail("更新失败");
        }
        log.info("端口[{}]更新主题为[{}]", port, topicName);
        Map<String, Object> resp = ok();
        resp.put("port", port);
        resp.put("topicName", topicName);
        return resp;
    }

    /**
     * 启用/禁用端口-主题绑定。
     */
    @PutMapping("/{port}/enabled")
    public Map<String, Object> toggleEnabled(@PathVariable int port,
                                             @RequestParam("enabled") boolean enabled) {
        PortTopicBinding existing = portTopicService.selectByPort(port);
        if (existing == null) {
            return fail("端口 " + port + " 未绑定主题");
        }
        boolean ok = portTopicService.updateEnabled(port, enabled);
        if (!ok) {
            return fail("操作失败");
        }
        Map<String, Object> resp = ok();
        resp.put("port", port);
        resp.put("enabled", enabled);
        return resp;
    }

    /**
     * 解绑端口-主题（逻辑删除：标记 enabled=false）。
     */
    @DeleteMapping("/{port}")
    public Map<String, Object> unbind(@PathVariable int port) {
        PortTopicBinding existing = portTopicService.selectByPort(port);
        if (existing == null) {
            return fail("端口 " + port + " 未绑定主题");
        }
        portTopicService.persistUnbind(port);
        log.info("端口[{}]已解绑主题[{}]", port, existing.getTopicName());
        Map<String, Object> resp = ok();
        resp.put("port", port);
        resp.put("topicName", existing.getTopicName());
        return resp;
    }

    /**
     * 物理删除端口-主题绑定记录。
     */
    @DeleteMapping("/{port}/force")
    public Map<String, Object> forceDelete(@PathVariable int port) {
        boolean ok = portTopicService.deleteByPort(port);
        if (!ok) {
            return fail("端口 " + port + " 不存在或删除失败");
        }
        log.info("端口[{}]主题绑定已物理删除", port);
        Map<String, Object> resp = ok();
        resp.put("port", port);
        return resp;
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
