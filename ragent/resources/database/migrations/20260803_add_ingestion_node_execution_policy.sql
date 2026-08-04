-- Apply after the base ingestion schema. Adds safe node-retry configuration and attempt observability.
ALTER TABLE t_ingestion_pipeline_node
    ADD COLUMN IF NOT EXISTS execution_policy_json JSONB;

ALTER TABLE t_ingestion_task_node
    ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 1;
