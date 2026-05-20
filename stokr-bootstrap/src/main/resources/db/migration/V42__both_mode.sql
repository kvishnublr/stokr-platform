ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS paired_order_id UUID;

CREATE TABLE execution_comparison_metrics (
    id                      UUID          PRIMARY KEY,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    deleted                 BOOLEAN       NOT NULL DEFAULT FALSE,
    signal_id               UUID,
    live_order_id           UUID REFERENCES oms_orders(id),
    paper_order_id          UUID REFERENCES oms_orders(id),
    strategy_key            VARCHAR(128),
    symbol                  VARCHAR(64),
    live_fill_price         NUMERIC(24,8),
    paper_fill_price        NUMERIC(24,8),
    slippage_divergence_pct NUMERIC(12,6),
    live_latency_ms         BIGINT,
    paper_latency_ms        BIGINT,
    pnl_divergence          NUMERIC(24,8)
);
