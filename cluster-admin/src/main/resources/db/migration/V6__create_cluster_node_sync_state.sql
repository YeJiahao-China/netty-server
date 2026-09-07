CREATE TABLE IF NOT EXISTS cluster_node_sync_state (
    node_id VARCHAR(255) NOT NULL,
    protocol_name VARCHAR(255) NOT NULL,
    sync_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    detail TEXT,
    last_sync_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (node_id, protocol_name)
);

CREATE INDEX IF NOT EXISTS idx_cluster_node_sync_state_protocol
    ON cluster_node_sync_state(protocol_name);

CREATE INDEX IF NOT EXISTS idx_cluster_node_sync_state_state
    ON cluster_node_sync_state(sync_state);
