-- V77: Move ADV_CASH into go-live cohort (BOTH paper+live, fixed qty=1) alongside V72 pilot strategies.

UPDATE strategy_definitions SET
    validation_status = 'LIVE_SHADOW',
    live_shadow_enabled = TRUE
WHERE strategy_key = 'ADV_CASH'
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
WHERE strategy_key = 'ADV_CASH'
  AND user_id IS NULL
  AND deleted = FALSE;
