-- =============================================================================
-- V3: 创建端口-主题绑定表
-- 用于将 TCP 监听端口接收到的数据桥接到对应的 RocketMQ LiteTopic。
-- =============================================================================
CREATE TABLE IF NOT EXISTS port_topic_binding (
    id              BIGSERIAL       PRIMARY KEY,
    port            INTEGER         NOT NULL,
    topic_name      VARCHAR(256)    NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_port_topic_binding_port UNIQUE (port)
);

COMMENT ON TABLE  port_topic_binding IS '端口-主题绑定表：TCP端口数据桥接到 RocketMQ LiteTopic';
COMMENT ON COLUMN port_topic_binding.port        IS 'TCP 监听端口';
COMMENT ON COLUMN port_topic_binding.topic_name  IS 'RocketMQ LiteTopic 名称';
COMMENT ON COLUMN port_topic_binding.enabled      IS '是否启用';

-- CREATE INDEX idx_port_topic_binding_enabled ON port_topic_binding (enabled);
