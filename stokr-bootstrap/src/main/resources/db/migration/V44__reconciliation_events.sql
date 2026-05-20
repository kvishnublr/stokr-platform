CREATE TABLE reconciliation_events (
    id                  UUID          PRIMARY KEY,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    deleted             BOOLEAN       NOT NULL DEFAULT FALSE,
    user_id             UUID          NOT NULL,
    broker_vendor       VARCHAR(32)   NOT NULL,
    symbol              VARCHAR(64),
    discrepancy_type    VARCHAR(64)   NOT NULL,
    broker_qty          NUMERIC(24,8),
    internal_qty        NUMERIC(24,8),
    delta               NUMERIC(24,8),
    order_id            UUID,
    status              VARCHAR(32)   NOT NULL DEFAULT 'OPEN',
    notes               VARCHAR(512),
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX idx_re_status ON reconciliation_events (status, created_at DESC) WHERE deleted = FALSE;
