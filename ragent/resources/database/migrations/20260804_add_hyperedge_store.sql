CREATE TABLE IF NOT EXISTS t_hyperedge (
    id                     VARCHAR(64) NOT NULL PRIMARY KEY,
    equipment              TEXT,
    condition              TEXT,
    parameter              TEXT,
    fault                  TEXT,
    sop_doc                TEXT,
    extended_entities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_document        VARCHAR(1024) NOT NULL,
    source_chunk_id        VARCHAR(128),
    source_chunk_index     INTEGER,
    source_page            INTEGER,
    document_version       VARCHAR(128),
    deleted                SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_hyperedge_document_active
    ON t_hyperedge (source_document)
    WHERE deleted = 0;
