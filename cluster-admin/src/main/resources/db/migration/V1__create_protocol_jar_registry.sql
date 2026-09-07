-- =============================================================================
-- V1: 创建协议 jar 注册表
-- 记录所有内置 + 外部协议的元数据快照，供「协议管理」页面持久化展示。
-- =============================================================================
CREATE TABLE IF NOT EXISTS protocol_jar_registry (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(128)    NOT NULL,
    version         VARCHAR(64)     NOT NULL DEFAULT '1.0.0',
    description     VARCHAR(512),
    source          VARCHAR(16)     NOT NULL,
    jar_path        VARCHAR(1024),
    provider_class  VARCHAR(512),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    deleted         SMALLINT        NOT NULL DEFAULT 0,
    loaded_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_protocol_jar_name UNIQUE (name, deleted)
);

COMMENT ON TABLE  protocol_jar_registry IS '协议 jar 注册表';
COMMENT ON COLUMN protocol_jar_registry.name           IS '协议唯一标识（如 hj212 / echo）';
COMMENT ON COLUMN protocol_jar_registry.version        IS '协议版本';
COMMENT ON COLUMN protocol_jar_registry.description    IS '协议描述';
COMMENT ON COLUMN protocol_jar_registry.source         IS '来源：builtin=内置  external=外部 jar';
COMMENT ON COLUMN protocol_jar_registry.jar_path       IS 'jar 文件绝对路径；内置协议为 NULL';
COMMENT ON COLUMN protocol_jar_registry.provider_class IS 'Provider 实现类全限定名';
COMMENT ON COLUMN protocol_jar_registry.active         IS '是否活跃可用';
COMMENT ON COLUMN protocol_jar_registry.deleted        IS '逻辑删除：0=未删 1=已删';
COMMENT ON COLUMN protocol_jar_registry.loaded_at      IS '加载时间';
COMMENT ON COLUMN protocol_jar_registry.created_at     IS '记录创建时间';
COMMENT ON COLUMN protocol_jar_registry.updated_at     IS '记录更新时间';

-- 创建常用查询索引
CREATE INDEX idx_protocol_jar_source  ON protocol_jar_registry (source);
CREATE INDEX idx_protocol_jar_active  ON protocol_jar_registry (active);
