-- Default equity strategies to BOTH (paper + live shadow) for drift reconciliation.
-- Keep CDS currency and E2E test strategies on PAPER-only.

UPDATE strategy_execution_configs
SET execution_mode   = 'BOTH',
    live_enabled     = TRUE,
    paper_enabled    = TRUE,
    updated_at       = NOW()
WHERE user_id IS NULL
  AND deleted = FALSE
  AND execution_mode = 'LIVE'
  AND strategy_key NOT IN (
      'USDINR_MOMENTUM',
      'EURINR_MEAN_REVERSION',
      'COMMODITIES_E2E_TEST'
  );
