-- A running task can be reclaimed only after its worker lease expires.
ALTER TABLE t_ingestion_task
    ADD COLUMN IF NOT EXISTS execution_lease_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;

-- Existing running rows predate leases and are treated as abandoned after deploy.
UPDATE t_ingestion_task
SET lease_expires_at = CURRENT_TIMESTAMP
WHERE status = 'running' AND lease_expires_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ingestion_task_lease
    ON t_ingestion_task (status, lease_expires_at)
    WHERE deleted = 0;
