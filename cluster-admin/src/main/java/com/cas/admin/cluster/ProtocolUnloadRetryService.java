package com.cas.admin.cluster;

import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.admin.common.NodeType;
import com.cas.admin.mapper.ProtocolJarRegistryMapper;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.entity.ProtocolJarRegistry;
import com.cas.cluster.node.service.ClusterNodeService;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.cas.cluster.node.constant.ProtocolConstants.INTERNAL_DISTRIBUTE_PATH;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UNLOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.STATUS_UNLOADED;
import static com.cas.cluster.node.constant.ProtocolConstants.SYNC_OUT_OF_SYNC;

/**
 * 协议卸载后台重试服务。
 *
 * <p>卸载是目标态操作：admin 首次广播后即返回响应（DB 已兜底 UNLOADED + 端口禁用），
 * 失败节点交给本服务在公用虚拟线程池中<b>立即异步重试一次</b>——不阻塞 HTTP 响应，
 * 也不占用 Tomcat 线程；只覆盖瞬时抖动（如广播超时但节点随后恢复），
 * 不做多次退避重试（持久性故障重试无意义，交由对账服务或人工收敛）。</p>
 *
 * <p>调用约定：必须由调用方在<b>操作锁完全释放之后</b>提交（unload 端点的 finally unlock 后），
 * 重试任务启动时锁必然空闲——tryLock 立即失败即为真实语义（确有管理操作进行中）、让位放弃。</p>
 *
 * <p>意图校验（fencing）：获得锁后先查 DB 协议状态，若提交到执行期间协议已被重新启用
 * （REGISTERED）或删除，说明卸载意图已失效，放弃重试——防止过期的卸载指令
 * 打掉管理员刚做出的新决策。</p>
 *
 * <p>重试仍失败的处置：
 * <ol>
 *   <li>ERROR 日志记录失败节点与处置指引；</li>
 *   <li>写 cluster_node_sync_state = OUT_OF_SYNC（detail 标注重试失败），
 *       供节点协议同步页展示，后续由对账服务周期性收敛或管理员介入；</li>
 *   <li>停止自动动作——正确性由 DB 目标态兜底（节点重启后不会加载该协议，状态必然收敛）。</li>
 * </ol>
 */
@Slf4j
@Component
public class ProtocolUnloadRetryService {

    private final CompensatingNodeBroadcastClient broadcast;
    private final ClusterNodeService nodeService;
    private final ClusterNodeSyncStateService syncStateService;
    private final ProtocolOperationGuard operationGuard;
    private final ProtocolJarRegistryMapper registryMapper;
    /** 公用后台任务线程池（AdminConfig#adminTaskExecutor），与其他后台任务复用，不区分业务类别 */
    private final ExecutorService adminTaskExecutor;

    public ProtocolUnloadRetryService(CompensatingNodeBroadcastClient broadcast,
                                      ClusterNodeService nodeService,
                                      ClusterNodeSyncStateService syncStateService,
                                      ProtocolOperationGuard operationGuard,
                                      ProtocolJarRegistryMapper registryMapper,
                                      @Qualifier("adminTaskExecutor") ExecutorService adminTaskExecutor) {
        this.broadcast = broadcast;
        this.nodeService = nodeService;
        this.syncStateService = syncStateService;
        this.operationGuard = operationGuard;
        this.registryMapper = registryMapper;
        this.adminTaskExecutor = adminTaskExecutor;
    }

    /**
     * 提交卸载后台重试任务（异步立即执行一次，不阻塞调用方）。
     *
     * <p><b>必须在操作锁释放后调用</b>（见类注释调用约定）。</p>
     *
     * @param protocolName      协议名（同时是操作锁 key）
     * @param failedNodeDetails 首次广播失败的节点明细（含 nodeId，来自卸载响应的 failedNodes）
     */
    public void submitAsyncRetry(String protocolName, List<Map<String, Object>> failedNodeDetails) {
        Set<String> pendingIds = failedNodeDetails.stream()
                .map(f -> String.valueOf(f.get("nodeId")))
                .collect(Collectors.toSet());
        List<ClusterNode> pendingNodes = nodeService.listUpByType(NodeType.ACCESS.name()).stream()
                .filter(n -> pendingIds.contains(n.getNodeId()))
                .toList();
        if (pendingNodes.isEmpty()) {
            log.info("协议[{}]卸载: 失败节点均已不在线，跳过后台重试（后续由对账服务收敛）: {}",
                    protocolName, pendingIds);
            return;
        }
        Map<String, Object> retryBody = new LinkedHashMap<>();
        retryBody.put("mode", MODE_UNLOAD);
        retryBody.put("protocolName", protocolName);
        log.info("协议[{}]卸载: {} 个失败节点已提交后台立即重试（异步 1 次）: {}",
                protocolName, pendingNodes.size(), pendingIds);
        try {
            adminTaskExecutor.submit(() -> {
                try {
                    doRetry(protocolName, retryBody, pendingNodes);
                } catch (Exception e) {
                    // submit(Runnable) 的异常若不捕获会被 Future 静默吞噬（无日志无标记），
                    // 此处必须兜底：记录异常并把节点标 OUT_OF_SYNC，交由对账服务周期收敛
                    log.error("协议[{}]卸载后台重试任务异常", protocolName, e);
                    for (ClusterNode node : pendingNodes) {
                        syncStateService.report(node.getNodeId(), protocolName, SYNC_OUT_OF_SYNC,
                                "unload 后台重试任务异常: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            // 提交失败（如停机时线程池已关闭）：不打断 unload 主流程，交由对账服务收敛
            log.warn("协议[{}]卸载后台重试任务提交失败（交由对账服务收敛）: {}", protocolName, e.getMessage());
        }
    }

    private void doRetry(String protocolName, Map<String, Object> body, List<ClusterNode> pendingNodes) {
        // 锁已由调用方释放后才提交，tryLock 立即失败 = 确有管理操作进行中 → 让位
        if (!operationGuard.tryLock(protocolName)) {
            log.info("协议[{}]卸载后台重试让位：检测到新的管理操作进行中，放弃重试", protocolName);
            return;
        }
        List<ClusterNode> stillFailed;
        try {
            // fencing 意图校验（锁内读取，与管理员操作互斥）：提交到执行期间协议被重新启用
            // 或删除，说明卸载意图已失效——过期的卸载指令不得打掉管理员刚做出的新决策
            ProtocolJarRegistry reg = registryMapper.selectByName(protocolName);
            if (reg == null || !STATUS_UNLOADED.equals(reg.getStatus())) {
                log.info("协议[{}]卸载后台重试中止：协议状态已变为{}，卸载意图已失效",
                        protocolName, reg == null ? "已删除" : reg.getStatus());
                return;
            }
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
            syncStateService.report(node.getNodeId(), protocolName, SYNC_OUT_OF_SYNC,
                    "unload 后台重试 1 次仍失败，等待人工检查或节点重启收敛");
        }
    }
}
