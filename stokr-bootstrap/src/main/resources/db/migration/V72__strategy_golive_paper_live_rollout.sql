-- V72: Go-live rollout — dual PAPER+LIVE (BOTH, fixed qty=1) for pilot strategies; others paper-only.

-- ── Go-live cohort: BOTH + fixed qty ─────────────────────────────────────────
UPDATE strategy_definitions SET
    validation_status = 'LIVE_SHADOW',
    live_shadow_enabled = TRUE
WHERE strategy_key IN ('GAP_FILL', 'NSE_SPIKE_DETECTION', 'VWAP_BOUNCE', 'SECTOR_LAGGARD')
  AND deleted = FALSE;

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
    capital_utilization_mode = 'FULLY_ALLOCATED',
    allow_fractional_capital_usage = FALSE
WHERE strategy_key IN ('GAP_FILL', 'NSE_SPIKE_DETECTION', 'VWAP_BOUNCE', 'SECTOR_LAGGARD')
  AND user_id IS NULL
  AND deleted = FALSE;

-- ── Paper-only cohort ────────────────────────────────────────────────────────
UPDATE strategy_definitions SET
    validation_status = 'PAPER_VALIDATING',
    live_shadow_enabled = FALSE
WHERE strategy_key IN ('EARLY_BREAKOUT', 'INDEX_HUNT', 'ADV_CASH', 'S3_VWAP_RETEST', 'S7_RANGE_FADE')
  AND deleted = FALSE;

UPDATE strategy_execution_configs SET
    execution_mode = 'PAPER',
    live_enabled = FALSE,
    paper_enabled = TRUE,
    sizing_mode = 'FIXED_QUANTITY',
    force_fixed_qty = TRUE,
    fixed_qty = 1,
    allocated_capital = NULL,
    max_capital_per_trade = NULL,
    max_total_exposure = NULL,
    capital_utilization_mode = 'FULLY_ALLOCATED'
WHERE strategy_key IN ('EARLY_BREAKOUT', 'INDEX_HUNT', 'ADV_CASH', 'S3_VWAP_RETEST', 'S7_RANGE_FADE')
  AND user_id IS NULL
  AND deleted = FALSE;

-- Ensure configs exist for go-live strategies (upsert via insert-if-missing)
INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, max_positions, deleted
)
SELECT gen_random_uuid(), sk, TRUE, 'BOTH', TRUE, TRUE,
       'FIXED_QUANTITY', TRUE, 1, mp, FALSE
FROM (VALUES
    ('GAP_FILL', 5),
    ('NSE_SPIKE_DETECTION', 5),
    ('VWAP_BOUNCE', 3),
    ('SECTOR_LAGGARD', 3)
) AS t(sk, mp)
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_execution_configs c
    WHERE c.strategy_key = t.sk AND c.user_id IS NULL AND c.deleted = FALSE
);
