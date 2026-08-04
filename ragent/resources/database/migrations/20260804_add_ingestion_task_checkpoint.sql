-- Durable upload-task checkpoints. The pipeline snapshot stays immutable so a
-- resume has the same topology and configuration that created the task.
ALTER TABLE t_ingestion_task
    ADD COLUMN IF NOT EXISTS pipeline_snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS checkpoint_json JSONB,
    ADD COLUMN IF NOT EXISTS last_success_node_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS next_node_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS resume_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS t_ingestion_task_payload (
    task_id VARCHAR(20) PRIMARY KEY,
    raw_bytes BYTEA NOT NULL,
    mime_type VARCHAR(255)
);

COMMENT ON TABLE t_ingestion_task_payload IS 'Upload-only task payload retained for checkpoint resume';
