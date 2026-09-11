package com.cas.admin.cluster;

import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 协议卸载后台重试服务。
 *
 * <p>卸载是目标态操作：admin 首次广播后即返回响应（DB 已兜底 UNLOADED + 端口禁用），
 * 失败节点交给本服务在公用虚拟线程池中<b>立即异步重试一次</b>——不阻塞 HTTP 响应，
 * 也不占用 Tomcat 线程；只覆盖瞬时抖动（如广播超时但节点随后恢复），
 * 不做多次退避重试（持久性故障重试无意义，交由人工或节点重启收敛）。</p>
 *
 * <p>与管理员操作的协调：重试前通过 {@link ProtocolOperationGuard}
 * 非阻塞竞争该协议的操作锁——若管理员已介入新操作（upload/reload/purge 等），
 * 后台重试立即让位放弃，避免与新操作交错。</p>
 *
 * <p>重试仍失败的处置：
 * <ol>
 *   <li>ERROR 日志记录失败节点与处置指引；</li>
 *   <li>写 cluster_node_sync_state = OUT_OF_SYNC（detail 标注重试失败），
 *       供节点协议同步页展示，管理员据此介入；</li>
 *   <li>停止自动动作——正确性由 DB 目标态兜底（节点重启后不会加载该协议，状态必然收敛）。</li>
 * </ol>
 */
@Slf4j
@Component
public class ProtocolUnloadRetryService {

    private static final String INTERNAL_DISTRIBUTE_PATH = "/protocols/v2/internal/distribute";

    private final CompensatingNodeBroadcastClient broadcast;
    private final ClusterNodeSyncStateService syncStateService;
    private final ProtocolOperationGuard operationGuard;
    /** 公用后台任务线程池（AdminConfig#adminTaskExecutor），与其他后台任务复用，不区分业务类别 */
    private final ExecutorService adminTaskExecutor;

    public ProtocolUnloadRetryService(CompensatingNodeBroadcastClient broadcast,
                                      ClusterNodeSyncStateService syncStateService,
                                      ProtocolOperationGuard operationGuard,
                                      @Qualifier("adminTaskExecutor") ExecutorService adminTaskExecutor) {
        this.broadcast = broadcast;
        this.syncStateService = syncStateService;
        this.operationGuard = operationGuard;
        this.adminTaskExecutor = adminTaskExecutor;
    }

    /**
     * 提交卸载后台重试任务（异步立即执行一次，不阻塞调用方）。
     *
     * @param protocolName 协议名（同时是操作锁 key）
     * @param body         卸载分发请求体（mode=unload）
     * @param failedNodes  首次广播失败的节点
     */
    public void submitAsyncRetry(String protocolName, Map<String, Object> body,
                                 List<NodeResult> failedNodes) {
        List<ClusterNode> pendingNodes = failedNodes.stream().map(NodeResult::getNode).toList();
        List<String> pendingNodeIds = failedNodes.stream().map(NodeResult::getNodeId).toList();
        Map<String, Object> retryBody = new LinkedHashMap<>(body);
        log.info("协议[{}]卸载: {} 个失败节点已提交后台立即重试（异步 1 次）: {}",
                protocolName, pendingNodes.size(), pendingNodeIds);
        adminTaskExecutor.submit(() -> doRetry(protocolName, retryBody, pendingNodes));
    }

    private void doRetry(String protocolName, Map<String, Object> body, List<ClusterNode> pendingNodes) {
        // 管理员已对该协议发起新操作：后台重试让位，避免指令交错
        if (!operationGuard.tryLock(protocolName)) {
            log.info("协议[{}]卸载后台重试让位：检测到新的管理操作进行中，放弃重试", protocolName);
            return;
        }
        List<ClusterNode> stillFailed;
        try {
            List<NodeResult> results = broadcast.broadcastExplicit(
                    pendingNodes, "POST", INTERNAL_DISTRIBUTE_PATH, body);
            stillFailed = results.stream()
                    .filter(r -> !r.isBusinessSuccess())
                    .map(NodeResult::getNode)
                    .toList();
        } finally {
            operationGuard.unlock(protocolName);
        }

        if (stillFailed.isEmpty()) {
            // 成功节点在 distribute 内已自行 syncUnload + 上报 SYNCED，无需 admin 再收口
            log.info("协议[{}]卸载后台重试成功: {} 个失败节点全部完成卸载", protocolName, pendingNodes.size());
            return;
        }

        // 重试仍失败：ERROR 日志 + 同步状态页标记，停止自动动作（DB 目标态兜底，节点重启后收敛）
        List<String> failedIds = stillFailed.stream().map(ClusterNode::getNodeId).toList();
        log.error("协议[{}]卸载后台重试仍失败: {}。"
                        + "DB 已标记 UNLOADED + 端口禁用，节点重启后将自动收敛；"
                        + "请检查失败节点健康状态（心跳/GC/磁盘/日志）",
                protocolName, failedIds);
        for (ClusterNode node : stillFailed) {
            syncStateService.report(node.getNodeId(), protocolName, "OUT_OF_SYNC",
                    "unload 后台重试 1 次仍失败，等待人工检查或节点重启收敛");
        }
    }
}
