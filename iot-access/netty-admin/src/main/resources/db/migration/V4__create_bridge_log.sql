-- =============================================================================
-- V4: 创建数据桥接日志表
-- 记录每次数据桥接（RocketMQ 发送）的成功/失败明细，用于问题排查和统计。
-- =============================================================================
CREATE TABLE IF NOT EXISTS bridge_log (
    id              BIGSERIAL       PRIMARY KEY,
    server_port     INTEGER         NOT NULL,
    server_ip       VARCHAR(64),
    client_port     INTEGER         NOT NULL,
    client_ip       VARCHAR(64),
    topic_name      VARCHAR(256),
    success         BOOLEAN         NOT NULL,
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    cost_ms         INTEGER,
    raw_data        TEXT,
    error_msg       TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  bridge_log              IS '数据桥接日志表：TCP→RocketMQ 发送明细';
COMMENT ON COLUMN bridge_log.server_port  IS '服务端 TCP 监听端口';
COMMENT ON COLUMN bridge_log.server_ip    IS '服务端 IP';
COMMENT ON COLUMN bridge_log.client_port  IS '客户端远端端口';
COMMENT ON COLUMN bridge_log.client_ip    IS '客户端远端 IP';
COMMENT ON COLUMN bridge_log.topic_name   IS '目标 RocketMQ topic';
COMMENT ON COLUMN bridge_log.success      IS '是否发送成功';
COMMENT ON COLUMN bridge_log.retry_count  IS '重试次数（0=首次即成功）';
COMMENT ON COLUMN bridge_log.cost_ms      IS '总耗时毫秒';
COMMENT ON COLUMN bridge_log.raw_data     IS '原始报文内容（完整保留）';
COMMENT ON COLUMN bridge_log.error_msg    IS '失败时的异常信息';
COMMENT ON COLUMN bridge_log.created_at   IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_bridge_log_server_port ON bridge_log (server_port);
CREATE INDEX IF NOT EXISTS idx_bridge_log_success     ON bridge_log (success);
CREATE INDEX IF NOT EXISTS idx_bridge_log_created_at  ON bridge_log (created_at);
