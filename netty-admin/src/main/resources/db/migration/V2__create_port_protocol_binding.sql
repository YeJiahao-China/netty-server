-- =============================================================================
-- V2: 创建端口-协议绑定表
-- 把 yml 中的 netty.server.ports + netty.server.protocol.bindings
-- 迁移到数据库，方便控制台「端口管理」页面 CRUD。
-- =============================================================================
CREATE TABLE IF NOT EXISTS port_protocol_binding (
    id              BIGSERIAL       PRIMARY KEY,
    port            INTEGER         NOT NULL,
    protocol_name   VARCHAR(128)    NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    deleted         SMALLINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_port_protocol_binding_port UNIQUE (port, deleted)
);

COMMENT ON TABLE  port_protocol_binding IS '端口-协议绑定表：每个 TCP 监听端口绑一种协议';
COMMENT ON COLUMN port_protocol_binding.port         IS 'TCP 监听端口';
COMMENT ON COLUMN port_protocol_binding.protocol_name IS '协议名（必须已注册）';
COMMENT ON COLUMN port_protocol_binding.enabled       IS '是否启用（启动时只监听 enabled=true 的端口）';
COMMENT ON COLUMN port_protocol_binding.deleted       IS '逻辑删除：0=未删 1=已删';

CREATE INDEX idx_port_protocol_binding_enabled ON port_protocol_binding (enabled);

-- 初始化默认绑定（与改造前 yml 配置保持一致）
INSERT INTO port_protocol_binding (port, protocol_name, enabled) VALUES
    (10101, 'hj212',       TRUE),
    (10102, 'echo',        TRUE),
    (10103, 'modbus-tcp',  TRUE)
ON CONFLICT DO NOTHING;
