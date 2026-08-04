-- Apply this migration to an existing PostgreSQL instance before deploying DAG routing.
CREATE TABLE IF NOT EXISTS t_ingestion_pipeline_edge (
    id             VARCHAR(20) NOT NULL PRIMARY KEY,
    pipeline_id    VARCHAR(20) NOT NULL,
    from_node_id   VARCHAR(20) NOT NULL,
    to_node_id     VARCHAR(20) NOT NULL,
    condition_json JSONB,
    priority       INTEGER     NOT NULL DEFAULT 0,
    default_edge   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by     VARCHAR(20) DEFAULT '',
    updated_by     VARCHAR(20) DEFAULT '',
    create_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ingestion_pipeline_edge_pipeline
    ON t_ingestion_pipeline_edge (pipeline_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_pipeline_edge_from
    ON t_ingestion_pipeline_edge (pipeline_id, from_node_id, priority DESC);
