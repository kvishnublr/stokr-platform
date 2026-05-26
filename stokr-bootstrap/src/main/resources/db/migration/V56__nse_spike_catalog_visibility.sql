-- V56: Ensure NSE_SPIKE_DETECTION is visible in the trader strategy catalog
-- Platform catalog scan can emit signals while the strategy was hidden from /api/strategies/catalog.

UPDATE strategy_definitions
SET enabled            = true,
    visible_to_users   = true,
    display_name       = COALESCE(NULLIF(TRIM(display_name), ''), 'NSE Spike Detection (1m)'),
    description        = COALESCE(
        NULLIF(TRIM(description), ''),
        '1m momentum spike strategy — detects sudden price+volume bursts on NSE equities using a composite score.'
    ),
    category           = COALESCE(category, 'INTRADAY'),
    risk_level         = COALESCE(risk_level, 'HIGH'),
    strategy_type      = COALESCE(strategy_type, 'INTRADAY'),
    default_timeframe  = COALESCE(default_timeframe, '1m'),
    supports_backtest  = true,
    supports_live      = true,
    supports_paper     = true,
    updated_at         = NOW()
WHERE strategy_key = 'NSE_SPIKE_DETECTION'
  AND deleted = false;

-- Keep intraday setup cards visible when present (V55 inserts).
UPDATE strategy_definitions
SET visible_to_users = true,
    updated_at       = NOW()
WHERE strategy_key IN ('GAP_FILL', 'VWAP_BOUNCE', 'SECTOR_LAGGARD', 'EARLY_BREAKOUT')
  AND deleted = false;
