-- Unified signal pipeline audit: every rejection/block with explicit reason (V8 truth engine)
CREATE TABLE IF NOT EXISTS signal_pipeline_audit (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID,
    strategy_key    VARCHAR(128) NOT NULL,
    symbol          VARCHAR(64)  NOT NULL,
    signal_id       UUID,
    pipeline_stage  VARCHAR(32)  NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    rejection_code  VARCHAR(64),
    rejection_message VARCHAR(512),
    requested_mode  VARCHAR(16),
    effective_mode  VARCHAR(16),
    confidence_score NUMERIC(10, 6),
    quality_gate    VARCHAR(32),
    risk_gate       VARCHAR(32),
    cooldown_sec_remaining INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_signal_pipeline_audit_created ON signal_pipeline_audit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_signal_pipeline_audit_symbol ON signal_pipeline_audit (symbol, strategy_key, created_at DESC);
