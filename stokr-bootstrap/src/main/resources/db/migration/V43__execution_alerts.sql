CREATE TABLE execution_alert_log (
    id              UUID          PRIMARY KEY,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    deleted         BOOLEAN       NOT NULL DEFAULT FALSE,
    alert_type      VARCHAR(64)   NOT NULL,
    strategy_key    VARCHAR(128),
    symbol          VARCHAR(64),
    order_id        UUID,
    user_id         UUID,
    payload_json    TEXT,
    delivered       BOOLEAN       NOT NULL DEFAULT FALSE,
    delivered_at    TIMESTAMPTZ
);

CREATE INDEX idx_eal_type_created ON execution_alert_log (alert_type, created_at DESC) WHERE deleted = FALSE;
