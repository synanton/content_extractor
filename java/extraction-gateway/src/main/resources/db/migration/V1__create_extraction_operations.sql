CREATE SEQUENCE extraction_completion_seq START 1;

CREATE TABLE extraction_operations (
    operation_id     TEXT PRIMARY KEY,
    tenant_id        TEXT NOT NULL,
    status           TEXT NOT NULL,
    progress         DOUBLE PRECISION NOT NULL DEFAULT 0,
    priority_class   TEXT NOT NULL,
    idempotency_key  TEXT NOT NULL,
    admission_verdict TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ,
    leased_until     TIMESTAMPTZ,
    completion_seq   BIGINT,
    error_code       TEXT,
    error_diagnostic TEXT,
    CONSTRAINT extraction_operations_status_check CHECK (
        status IN ('ACCEPTED','QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED','EXPIRED')
    )
);

CREATE TABLE extraction_operation_items (
    operation_id     TEXT NOT NULL REFERENCES extraction_operations(operation_id) ON DELETE CASCADE,
    item_index       INT NOT NULL,
    content_ref_id   TEXT NOT NULL,
    media_type       TEXT NOT NULL,
    source_bucket    TEXT NOT NULL,
    source_key       TEXT NOT NULL,
    source_version   TEXT,
    source_sha256    TEXT NOT NULL,
    source_size      BIGINT NOT NULL,
    status           TEXT NOT NULL,
    progress         DOUBLE PRECISION NOT NULL DEFAULT 0,
    feature_states   JSONB NOT NULL DEFAULT '{}',
    error_code       TEXT,
    error_diagnostic TEXT,
    PRIMARY KEY (operation_id, item_index)
);

CREATE TABLE extraction_idempotency (
    tenant_id        TEXT NOT NULL,
    idempotency_key  TEXT NOT NULL,
    request_hash     TEXT NOT NULL,
    operation_id     TEXT NOT NULL REFERENCES extraction_operations(operation_id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE TABLE extraction_results (
    tenant_id        TEXT NOT NULL,
    operation_id     TEXT NOT NULL,
    item_index       INT NOT NULL,
    result_json      JSONB NOT NULL,
    processor_id     TEXT,
    source_sha256    TEXT,
    PRIMARY KEY (tenant_id, operation_id, item_index)
);

CREATE INDEX idx_extraction_operations_tenant_active
    ON extraction_operations (tenant_id)
    WHERE status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED','EXPIRED');

CREATE INDEX idx_extraction_operations_queued
    ON extraction_operations (created_at)
    WHERE status = 'QUEUED';

CREATE INDEX idx_extraction_operations_completion_seq
    ON extraction_operations (completion_seq)
    WHERE completion_seq IS NOT NULL;
