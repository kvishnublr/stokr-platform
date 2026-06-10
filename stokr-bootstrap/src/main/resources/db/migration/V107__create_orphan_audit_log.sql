-- V107: Create Orphan Audit Log Table
-- Creates audit trail for compliance and investigation

CREATE TABLE IF NOT EXISTS orphan_audit_log (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,

    orphan_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_source VARCHAR(64),

    actor_id UUID,
    actor_type VARCHAR(32),

    event_details TEXT,

    user_id UUID,
    symbol VARCHAR(64),
    classification_type VARCHAR(32),

    retention_date TIMESTAMPTZ
);

CREATE INDEX idx_audit_orphan ON orphan_audit_log(orphan_id);
CREATE INDEX idx_audit_type ON orphan_audit_log(event_type);
CREATE INDEX idx_audit_actor ON orphan_audit_log(actor_id);
CREATE INDEX idx_audit_date ON orphan_audit_log(created_at DESC);
CREATE INDEX idx_audit_symbol ON orphan_audit_log(symbol);
CREATE INDEX idx_audit_retention ON orphan_audit_log(retention_date) WHERE retention_date IS NOT NULL;

-- Partition audit log by month for performance
CREATE TABLE orphan_audit_log_2026_06 PARTITION OF orphan_audit_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
