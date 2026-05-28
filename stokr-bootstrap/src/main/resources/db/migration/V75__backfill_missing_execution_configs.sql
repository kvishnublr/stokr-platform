-- V75: Backfill strategy_execution_configs for catalog strategies missing sizing rows.
-- Without these rows, OMS rejects signals with SIZING_REJECTED / NO_EXECUTION_CONFIG.

INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, max_positions, capital_utilization_mode, deleted
)
SELECT
    gen_random_uuid(),
    sd.strategy_key,
    TRUE,
    CASE
        WHEN sd.execution_mode IN ('PAPER', 'LIVE', 'BOTH') THEN sd.execution_mode
        ELSE 'BOTH'
    END,
    sd.supports_live,
    sd.supports_paper,
    'FIXED_QUANTITY',
    TRUE,
    1,
    COALESCE(
        (SELECT srb.max_positions FROM strategy_runtime_bindings srb
         WHERE srb.strategy_catalog_id = sd.id AND srb.runtime_enabled = TRUE
         ORDER BY srb.updated_at DESC NULLS LAST LIMIT 1),
        3
    ),
    'FULLY_ALLOCATED',
    FALSE
FROM strategy_definitions sd
WHERE sd.deleted = FALSE
  AND sd.enabled = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM strategy_execution_configs c
      WHERE c.strategy_key = sd.strategy_key
        AND c.user_id IS NULL
        AND c.deleted = FALSE
  );

-- Explicit defaults for high-traffic scanners / MCX strategies (paper+LIVE BOTH, qty=1)
UPDATE strategy_execution_configs SET
    execution_mode = 'BOTH',
    live_enabled = TRUE,
    paper_enabled = TRUE,
    sizing_mode = 'FIXED_QUANTITY',
    force_fixed_qty = TRUE,
    fixed_qty = 1,
    allocated_capital = NULL,
    max_capital_per_trade = NULL,
    max_total_exposure = NULL,
    capital_utilization_mode = 'FULLY_ALLOCATED'
WHERE strategy_key IN (
    'OPENING_RANGE_BREAKOUT',
    'VWAP_MEAN_REVERSION',
    'BREAKOUT_COMMODITIES',
    'COMMODITIES_E2E_TEST'
)
AND user_id IS NULL
AND deleted = FALSE;
