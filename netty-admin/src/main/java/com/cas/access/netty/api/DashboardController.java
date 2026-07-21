package com.cas.access.netty.api;

import com.cas.access.netty.entity.ProtocolJarRegistry;
import com.cas.access.netty.protocol.ProtocolRegistry;
import com.cas.access.netty.server.GlobalCache;
import com.cas.access.netty.service.ProtocolJarRegistryService;
import io.netty.channel.Channel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据接入控制台页面控制器（SSR）。
 *
 * <p>仅负责渲染 Thymeleaf 模板的初始视图。所有运维操作（reload、bind、unbind、unload）
 * 由前端 JavaScript 调用 {@link ProtocolController} 暴露的 REST 接口完成。
 *
 * <p>页面路由：
 * <ul>
 *   <li>{@code GET /}               → 概览</li>
 *   <li>{@code GET /protocols-page} → 协议管理</li>
 *   <li>{@code GET /connections}    → 连接管理（占位）</li>
 *   <li>{@code GET /ports}          → 端口管理（占位）</li>
 *   <li>{@code GET /traffic}        → 流量监控（占位）</li>
 *   <li>{@code GET /logs}           → 系统日志</li>
 *   <li>{@code GET /api-docs}       → API 参考</li>
 * </ul>
 *
 * <p>访问入口：{@code http://localhost:2310/}
 *
 * @author yjh_c
 */
@Controller
public class DashboardController {

    @Resource
    private ProtocolRegistry registry;

    @Resource
    private ProtocolJarRegistryService protocolJarRegistryService;

    /* ===================== 概览 ===================== */

    @GetMapping("/")
    public String dashboard(Model model) {
        buildFullModel(model);
        return "dashboard";
    }

    /* ===================== 协议管理 ===================== */

    @GetMapping("/protocols-page")
    public String protocolsPage(Model model) {
        buildFullModel(model);
        return "protocols-page";
    }

    /* ===================== 占位 / 信息页 ===================== */

    @GetMapping("/connections")
    public String connections() {
        return "connections";
    }

    @GetMapping("/ports")
    public String ports() {
        return "ports";
    }

    @GetMapping("/traffic")
    public String traffic() {
        return "traffic";
    }

    @GetMapping("/logs")
    public String logs() {
        return "logs";
    }

    @GetMapping("/api-docs")
    public String apiDocs() {
        return "api-docs";
    }

    /* ===================== 私有：构建通用视图数据 ===================== */

    /**
     * 构建概览/协议管理页所需的视图数据：protocols、bindings、summary。
     */
    private void buildFullModel(Model model) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // 协议列表（从数据库查询所有协议，包括已删除和未启用的）
        List<Map<String, Object>> protocols = new ArrayList<>();
        int externalJarCount = 0;
        for (ProtocolJarRegistry p : protocolJarRegistryService.listAllInDb()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("version", p.getVersion());
            m.put("description", p.getDescription() == null ? "" : p.getDescription());
            m.put("source", p.getSource());
            m.put("loadedAtText", p.getLoadedAt() == null ? "" : fmt.format(java.sql.Timestamp.valueOf(p.getLoadedAt())));
            m.put("active", p.getActive());
            m.put("deleted", p.getDeleted());
            m.put("jarPath", p.getJarPath());
            protocols.add(m);
            if ("external".equals(p.getSource())) {
                externalJarCount++;
            }
        }
        model.addAttribute("protocols", protocols);

        // 端口绑定（含监听状态 + 活跃连接数）
        List<Map<String, Object>> bindings = new ArrayList<>();
        int totalConnections = 0;
        for (Map.Entry<Integer, String> e : registry.getAllBindings().entrySet()) {
            int port = e.getKey();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("port", port);
            b.put("protocol", e.getValue());
            b.put("listening", GlobalCache.ServerPort_ServerSocketChannel_Map.containsKey(port));
            Set<Channel> channels = GlobalCache.ServerPost_SocketChannelSet_Map.get(port);
            int conns = channels == null ? 0 : channels.size();
            b.put("connections", conns);
            bindings.add(b);
            totalConnections += conns;
        }
        model.addAttribute("bindings", bindings);

        // 顶部概览
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("protocolCount", protocols.size());
        summary.put("externalJarCount", externalJarCount);
        summary.put("boundPortCount", bindings.size());
        summary.put("totalConnections", totalConnections);
        model.addAttribute("summary", summary);
    }
}
