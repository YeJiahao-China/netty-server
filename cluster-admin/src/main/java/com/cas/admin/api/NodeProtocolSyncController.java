package com.cas.admin.api;

import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.entity.ClusterNodeSyncState;
import com.cas.cluster.node.service.ClusterNodeService;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 节点协议同步状态查询与展示。
 *
 * <p>本类作为新增 Controller 存在，不修改现有 {@link ClusterNodeController}。</p>
 */
@Controller
@RequestMapping("/node-protocol-sync")
@RequiredArgsConstructor
public class NodeProtocolSyncController {

    private final ClusterNodeSyncStateService syncStateService;
    private final ClusterNodeService nodeService;

    /**
     * 页面：节点协议同步状态总览。
     */
    @GetMapping
    public String page(Model model) {
        List<ClusterNode> nodes = nodeService.listByType(null);
        List<ClusterNodeSyncState> states = syncStateService.listAll();
        Map<String, String> nodeNameMap = nodes.stream()
                .collect(Collectors.toMap(ClusterNode::getNodeId,
                        n -> n.getNodeName() != null ? n.getNodeName() : n.getNodeId(),
                        (a, b) -> a));

        model.addAttribute("nodes", nodes);
        model.addAttribute("states", states);
        model.addAttribute("nodeNameMap", nodeNameMap);
        return "node-protocol-sync-page";
    }

    /**
     * API：查询某协议在所有节点上的同步状态。
     */
    @GetMapping("/api/protocol/{protocolName}")
    @ResponseBody
    public Map<String, Object> byProtocol(@PathVariable String protocolName) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolName", protocolName);
        r.put("states", syncStateService.listByProtocol(protocolName));
        return r;
    }

    /**
     * API：查询某节点上所有协议的同步状态。
     */
    @GetMapping("/api/node/{nodeId}")
    @ResponseBody
    public Map<String, Object> byNode(@PathVariable String nodeId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodeId", nodeId);
        r.put("states", syncStateService.listByNode(nodeId));
        return r;
    }

    /**
     * API：查询全部同步状态。
     */
    @GetMapping("/api/all")
    @ResponseBody
    public Map<String, Object> all() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("states", syncStateService.listAll());
        return r;
    }
}
