package com.cas.cluster.node.constant;

/**
 * 协议热插拔体系共享常量。
 *
 * <p>cluster-admin 与 iot-access(netty-admin) 共用的状态值 / 分发模式 / 分发路径，
 * 统一收敛到此，避免魔法字符串散落各文件导致拼写漂移。</p>
 *
 * <p>注意：均为编译期常量（字面量初始化），可直接用于 switch case。</p>
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    /* ========== protocol_jar_registry.status 协议状态 ========== */

    /** 初始：jar 已入 DB 仓库但尚未分发到节点 */
    public static final String STATUS_INIT = "INIT";

    /** 已注册：协议在节点运行时加载并监听端口 */
    public static final String STATUS_REGISTERED = "REGISTERED";

    /** 已卸载（目标态）：节点重启后读 DB 不会加载该协议 */
    public static final String STATUS_UNLOADED = "UNLOADED";

    /** 分发失败：上次分发（upload/update）未成功且已回滚 */
    public static final String STATUS_FAILED = "FAILED";

    /* ========== cluster_node_sync_state.sync_state 节点协议同步状态 ========== */

    /** 节点与 DB 期望状态一致 */
    public static final String SYNC_SYNCED = "SYNCED";

    /** 节点与 DB 期望状态不一致（操作在该节点执行失败） */
    public static final String SYNC_OUT_OF_SYNC = "OUT_OF_SYNC";

    /** 待确认（节点 DOWN 期间状态未知，恢复后需对账/补发） */
    public static final String SYNC_PENDING = "PENDING";

    /** 未知（无法归类，如 cleanup 后协议已不存在） */
    public static final String SYNC_UNKNOWN = "UNKNOWN";

    /* ========== cluster_node.status 节点状态 ========== */

    public static final String NODE_UP = "UP";
    public static final String NODE_DOWN = "DOWN";

    /* ========== V2 分发 mode（distribute 请求体的 mode 字段）========== */

    /** 上传新 jar 并绑定端口（携带 jarBase64） */
    public static final String MODE_UPLOAD = "upload";
    /** 上传新 jar 并绑定端口（节点从 DB 拉 jar_bytes） */
    public static final String MODE_SYNC_UPLOAD = "sync-upload";
    /** 更新 jar 热替换（携带 jarBase64，自动备份旧 jar） */
    public static final String MODE_UPDATE = "update";
    /** 更新 jar 热替换（节点从 DB 拉新 jar） */
    public static final String MODE_SYNC_UPDATE = "sync-update";
    /** 绑定协议到端口并开启监听 */
    public static final String MODE_BIND = "bind";
    /** 解绑端口并关闭监听 */
    public static final String MODE_UNBIND = "unbind";
    /** 重新启用单个协议（从 DB 拉 jar + 恢复端口监听） */
    public static final String MODE_RELOAD = "reload";
    /** 卸载协议（关端口监听、踢连接、destroy Provider、close ClassLoader） */
    public static final String MODE_UNLOAD = "unload";
    /** 彻底删除（清理节点本地 jar + 删除 DB 记录） */
    public static final String MODE_PURGE = "purge";
    /** upload 失败的补偿回滚：unload + 删本地 jar + 删 DB 记录 */
    public static final String MODE_CLEANUP_UPLOAD = "cleanup-upload";
    /** update 失败的补偿回滚：从 .backup 恢复旧版本 jar */
    public static final String MODE_ROLLBACK_UPDATE = "rollback-update";

    /** V2 分发接口路径：cluster-admin 广播 → iot-access 接收 */
    public static final String INTERNAL_DISTRIBUTE_PATH = "/protocols/v2/internal/distribute";
}
