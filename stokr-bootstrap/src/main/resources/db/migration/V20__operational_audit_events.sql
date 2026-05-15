-- Operational audit trail for admin actions and high-signal platform events (append-only).

CREATE TABLE IF NOT EXISTS operational_audit_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    topic VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    actor VARCHAR(128),
    target_user_id UUID
);

CREATE INDEX IF NOT EXISTS idx_operational_audit_created ON operational_audit_events (created_at DESC) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_operational_audit_topic ON operational_audit_events (topic) WHERE deleted = FALSE;
