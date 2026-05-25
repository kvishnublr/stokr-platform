-- Signal provenance: isolate LIVE / PAPER / REPLAY / LAB for quant-safe analytics
ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS signal_source VARCHAR(16);

UPDATE strategy_signals SET signal_source = 'LAB' WHERE is_test_trade = TRUE AND (signal_source IS NULL OR signal_source = '');
UPDATE strategy_signals SET signal_source = 'PAPER' WHERE pipeline = 'PAPER' AND (signal_source IS NULL OR signal_source = '');
UPDATE strategy_signals SET signal_source = 'LIVE' WHERE (signal_source IS NULL OR signal_source = '');

-- Historical replay burst (synthetic seed + admin replay) — adjust window if needed
UPDATE strategy_signals
SET signal_source = 'REPLAY'
WHERE signal_source = 'LIVE'
  AND is_test_trade = FALSE
  AND backtest_run_id IS NULL
  AND created_at >= TIMESTAMPTZ '2026-05-20 00:00:00+00'
  AND created_at <  TIMESTAMPTZ '2026-05-26 00:00:00+00';

CREATE INDEX IF NOT EXISTS idx_strategy_signals_source_created
    ON strategy_signals (signal_source, created_at DESC)
    WHERE deleted = FALSE;

-- Kite order_id (string) for fill synchronization
ALTER TABLE oms_orders
    ADD COLUMN IF NOT EXISTS broker_external_order_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_oms_orders_broker_external
    ON oms_orders (broker_external_order_id)
    WHERE deleted = FALSE AND broker_external_order_id IS NOT NULL;
