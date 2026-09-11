package com.cas.admin.cluster;

import com.cas.admin.cluster.CompensatingNodeBroadcastClient.NodeResult;
import com.cas.admin.common.NodeType;
import com.cas.admin.mapper.ProtocolJarRegistryMapper;
import com.cas.cluster.node.entity.ClusterNode;
import com.cas.cluster.node.entity.ClusterNodeSyncState;
import com.cas.cluster.node.entity.ProtocolJarRegistry;
import com.cas.cluster.node.service.ClusterNodeService;
import com.cas.cluster.node.service.ClusterNodeSyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.cas.cluster.node.constant.ProtocolConstants.INTERNAL_DISTRIBUTE_PATH;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_PURGE;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_RELOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UNLOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.STATUS_REGISTERED;
import static com.cas.cluster.node.constant.ProtocolConstants.STATUS_UNLOADED;
import static com.cas.cluster.node.constant.ProtocolConstants.SYNC_OUT_OF_SYNC;
import static com.cas.cluster.node.constant.ProtocolConstants.SYNC_PENDING;
import static com.cas.cluster.node.constant.ProtocolConstants.SYNC_SYNCED;
import static com.cas.cluster.node.constant.ProtocolConstants.SYNC_UNKNOWN;

/**
 * 协议对账服务（周期性 Reconcile / Anti-Entropy）。
 *
 * <p><b>定位</b>：多节点同步操作的公共兜底层（Nacos Distro 式收敛模型）。
 * 广播、后台立即重试、节点重启读 DB 三层覆盖不到的场景由本层周期抹平：
 * <ul>
 *   <li>后台重试仍失败的节点；</li>
 *   <li>admin 重启丢失的异步重试任务；</li>
 *   <li>节点失联期间错过全部操作、恢复 UP 后无人补发（{@code NodeSyncStateScheduler}
 *       已将其状态标为 PENDING，本服务据此补发）；</li>
 *   <li>purge 后 DB 记录已删、节点残留运行时协议/jar 的"孤儿"（反向对账）。</li>
 * </ul>
 *
 * <p><b>通用性设计</b>：不针对具体操作（upload/update/bind/unload/purge）各写一套逻辑，
 * 而是一张「DB 期望状态 × 节点同步状态 → 补发 mode」的统一映射
 * （见 {@link #resolveAction}）。补发动作与人工操作走同一条 V2 分发通道，
 * 节点侧零新增接口；未来新增操作类型只需扩展映射规则。
 * 补发方向永远由<b>补发时刻的 DB 期望状态</b>决定，不存在过期意图
 * （管理员中途改变决策，下一轮对账自动按新期望收敛）。
 *
 * <p><b>不对账干预的期望状态</b>：
 * <ul>
 *   <li>{@code INIT}：jar 从未分发到节点，无节点状态可比对；</li>
 *   <li>{@code FAILED}：上次分发已保守回滚，属人工决策范畴。</li>
 * </ul>
 *
 * <p><b>已知边界</b>（初版状态级对账）：
 * <ul>
 *   <li>update 失败回滚后的"版本级漂移"（状态一致但版本不同）发现不了；</li>
 *   <li>unbind 仅按端口操作、不写协议维度 sync_state，端口级差异依赖
 *       期望 REGISTERED 场景的补 reload 顺带恢复。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolReconcileService {

    /** 同一 (协议, 节点) 连续补发轮次上限：超过说明是持久性故障，暂停自动补发等人工处理 */
    private static final int MAX_CONSECUTIVE_ROUNDS = 3;

    private final ClusterNodeService nodeService;
    private final ClusterNodeSyncStateService syncStateService;
    private final ProtocolJarRegistryMapper registryMapper;
    private final CompensatingNodeBroadcastClient broadcast;
    private final ProtocolOperationGuard operationGuard;

    /** 防抖计数：key = protocolName|nodeId → 连续补发轮数；状态恢复一致后自动清零 */
    private final ConcurrentHashMap<String, Integer> consecutiveRounds = new ConcurrentHashMap<>();

    /** 一条待修正项：向某节点补发某 mode 及原因描述 */
    private record ReconcileAction(String protocolName, ClusterNode node, String mode, String reason) {
    }

    /**
     * 执行一轮对账：采集 → diff → 防抖过滤 → 逐项补发。
     * 单项异常不影响其余项；整体异常由调度层捕获。
     */
    public void reconcile() {
        // 1. 采集三份数据（均为现有查询，无新增 SQL）
        List<ClusterNode> upNodes = nodeService.listUpByType(NodeType.ACCESS.name());
        if (upNodes.isEmpty()) {
            return;
        }
        Map<String, ClusterNode> upNodeById = new LinkedHashMap<>();
        for (ClusterNode n : upNodes) {
            upNodeById.put(n.getNodeId(), n);
        }
        Map<String, ProtocolJarRegistry> expectByName = new LinkedHashMap<>();
        for (ProtocolJarRegistry p : registryMapper.selectAll()) {
            expectByName.put(p.getName(), p);
        }
        List<ClusterNodeSyncState> syncStates = syncStateService.listAll();

        // 2. diff：通用映射规则决定每个 (协议, 节点) 是否需要补发、补发什么
        List<ReconcileAction> actions = new ArrayList<>();
        for (ClusterNodeSyncState st : syncStates) {
            ClusterNode node = upNodeById.get(st.getNodeId());
            if (node == null) {
                continue; // DOWN 节点不对账：等恢复 UP（其状态已被标 PENDING，届时自动纳入）
            }
            String key = st.getProtocolName() + "|" + st.getNodeId();
            ProtocolJarRegistry expect = expectByName.get(st.getProtocolName());
            String mode = resolveAction(expect, st.getSyncState());
            if (mode == null) {
                consecutiveRounds.remove(key); // 状态一致 → 防抖计数清零
                continue;
            }
            // 节点经历 DOWN→恢复（PENDING）视为新生命周期事件：重置防抖计数给予新的补发机会。
            // 否则计数超限后节点即使恢复也永远不会再被补发（防抖死锁，状态永远停在 PENDING）
            if (SYNC_PENDING.equals(st.getSyncState())) {
                consecutiveRounds.remove(key);
            }
            int rounds = consecutiveRounds.merge(key, 1, Integer::sum);
            if (rounds > MAX_CONSECUTIVE_ROUNDS) {
                log.warn("协议对账防抖: [{}] 连续 {} 轮补发未收敛，暂停自动补发等待人工处理 "
                                + "（状态恢复一致后自动重新纳入）", key, MAX_CONSECUTIVE_ROUNDS);
                continue;
            }
            String reason = describe(expect, st);
            log.info("协议对账发现差异: {} → 补发 {}（{}）", key, mode, reason);
            actions.add(new ReconcileAction(st.getProtocolName(), node, mode, reason));
        }

        // 3. 逐项补发：tryLock 与管理员操作/后台重试协调，拿不到锁跳过下轮再看
        for (ReconcileAction action : actions) {
            dispatchSafely(action);
        }
    }

    /**
     * 通用规则引擎：DB 期望状态 + 节点同步状态 → 补发 mode。
     *
     * @return 需要补发的 mode；null 表示无需动作（状态一致，或期望状态不对账干预）
     */
    private String resolveAction(ProtocolJarRegistry expect, String syncState) {
        if (expect == null) {
            // 反向对账（purge 孤儿）：DB 记录已删但节点状态未确认 → 补 purge 清理节点残留
            return SYNC_SYNCED.equals(syncState) ? null : MODE_PURGE;
        }
        String status = expect.getStatus();
        if (STATUS_REGISTERED.equals(status)) {
            // 期望启用：节点未确认同步（含 DOWN 恢复后的 PENDING）→ 补 reload（顺带恢复端口监听）
            return needsFix(syncState) ? MODE_RELOAD : null;
        }
        if (STATUS_UNLOADED.equals(status)) {
            // 期望卸载：节点未确认同步 → 补 unload
            return needsFix(syncState) ? MODE_UNLOAD : null;
        }
        // INIT（从未分发）/ FAILED（已回滚待人工）不对账干预
        return null;
    }

    /** 节点同步状态是否属于"未确认一致"，需要对账修正 */
    private boolean needsFix(String syncState) {
        return SYNC_OUT_OF_SYNC.equals(syncState)
                || SYNC_PENDING.equals(syncState)
                || SYNC_UNKNOWN.equals(syncState);
    }

    private String describe(ProtocolJarRegistry expect, ClusterNodeSyncState st) {
        String expectDesc = expect == null
                ? "DB无记录(已purge)"
                : "期望=" + expect.getStatus();
        return expectDesc + ", 节点上报=" + st.getSyncState();
    }

    /** 补发单项：独立 try-catch，失败/锁冲突不影响其余项 */
    private void dispatchSafely(ReconcileAction action) {
        try {
            if (!operationGuard.tryLock(action.protocolName())) {
                log.info("协议对账补发让位: 协议[{}]有管理操作进行中，本轮跳过", action.protocolName());
                return;
            }
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("mode", action.mode());
                body.put("protocolName", action.protocolName());
                List<NodeResult> results = broadcast.broadcastExplicit(
                        List.of(action.node()), "POST", INTERNAL_DISTRIBUTE_PATH, body);
                boolean ok = !results.isEmpty() && results.getFirst().isBusinessSuccess();
                if (ok) {
                    log.info("协议对账补发完成: 协议[{}] 节点[{}] mode={}（{}）",
                            action.protocolName(), action.node().getNodeId(), action.mode(), action.reason());
                } else {
                    log.warn("协议对账补发失败: 协议[{}] 节点[{}] mode={}（{}）",
                            action.protocolName(), action.node().getNodeId(), action.mode(), action.reason());
                }
            } finally {
                operationGuard.unlock(action.protocolName());
            }
        } catch (Exception e) {
            log.warn("协议对账补发异常: 协议[{}] 节点[{}], err={}",
                    action.protocolName(), action.node().getNodeId(), e.getMessage());
        }
    }

    /** 供监控/测试观察当前防抖计数 */
    Map<String, Integer> getConsecutiveRoundsSnapshot() {
        return Map.copyOf(consecutiveRounds);
    }
}
