ALTER TABLE t_ingestion_task
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

UPDATE t_ingestion_task
SET idempotency_key = id
WHERE idempotency_key IS NULL;

ALTER TABLE t_ingestion_task
    ALTER COLUMN idempotency_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_task_active_idempotency
    ON t_ingestion_task (idempotency_key)
    WHERE deleted = 0 AND status IN ('running', 'completed');
