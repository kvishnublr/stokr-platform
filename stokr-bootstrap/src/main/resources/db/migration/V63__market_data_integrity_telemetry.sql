-- P0 market data integrity telemetry — fail-closed rejection audit trail

CREATE TABLE IF NOT EXISTS market_data_integrity_rejections (
    id                  BIGSERIAL PRIMARY KEY,
    strategy_name       VARCHAR(128) NOT NULL,
    symbol              VARCHAR(64),
    rejection_reason    VARCHAR(128) NOT NULL,
    latest_bar_time     TIMESTAMPTZ,
    expected_bar_time   TIMESTAMPTZ,
    session_date        DATE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_md_integrity_rejections_session
    ON market_data_integrity_rejections (session_date DESC, strategy_name);

CREATE INDEX IF NOT EXISTS idx_md_integrity_rejections_created
    ON market_data_integrity_rejections (created_at DESC);
