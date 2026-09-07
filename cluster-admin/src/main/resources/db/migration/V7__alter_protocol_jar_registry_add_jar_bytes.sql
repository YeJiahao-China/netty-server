-- =============================================================================
-- V7: 扩展 protocol_jar_registry 表
-- 新增 jar_bytes（协议 jar 二进制仓库）、status（注册状态）、failure_detail（失败明细）
-- jar_path 保留（节点本地落盘路径），jar_bytes 作为统一仓库供新节点同步。
-- =============================================================================
ALTER TABLE protocol_jar_registry ADD COLUMN IF NOT EXISTS jar_bytes       BYTEA;
ALTER TABLE protocol_jar_registry ADD COLUMN IF NOT EXISTS status          VARCHAR(20)  NOT NULL DEFAULT 'INIT';
ALTER TABLE protocol_jar_registry ADD COLUMN IF NOT EXISTS failure_detail   TEXT;

COMMENT ON COLUMN protocol_jar_registry.jar_bytes      IS '协议 jar 二进制内容（供新节点从 DB 拉取同步）';
COMMENT ON COLUMN protocol_jar_registry.status         IS '注册状态: INIT（初始入库）/ REGISTERED（所有节点注册成功）/ FAILED（部分节点失败）';
COMMENT ON COLUMN protocol_jar_registry.failure_detail  IS '注册失败明细 (JSON: [{nodeId, host, error}])';
