-- 集群节点注册表（iot-access / iot-parser 共享；本表由 iot-access 的 Flyway 创建，iot-parser 复用同库）
CREATE TABLE IF NOT EXISTS cluster_node (
    node_id         VARCHAR(64)  PRIMARY KEY,                 -- 节点唯一键 host:port
    node_type       VARCHAR(16)  NOT NULL,                     -- 节点类型 ACCESS / PARSER
    host            VARCHAR(64)  NOT NULL,
    port            INT          NOT NULL,
    node_name       VARCHAR(128),
    status          VARCHAR(8)   NOT NULL DEFAULT 'DOWN',      -- UP / DOWN
    last_heartbeat  TIMESTAMP,
    registered_at   TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  cluster_node               IS '集群节点注册表（iot-access / iot-parser 共享）';
COMMENT ON COLUMN cluster_node.node_id       IS '节点唯一键 host:port';
COMMENT ON COLUMN cluster_node.node_type     IS '节点类型 ACCESS / PARSER';
COMMENT ON COLUMN cluster_node.status        IS '节点状态 UP / DOWN';
COMMENT ON COLUMN cluster_node.last_heartbeat IS '最近一次心跳时间，超时未心跳由扫描任务置 DOWN';
