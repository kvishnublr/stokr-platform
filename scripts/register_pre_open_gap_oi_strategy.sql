-- ============================================================
-- Register PRE_OPEN_GAP_OI strategy in strategy_definitions
-- Run once on your DB before deploying the new service build
-- ============================================================

INSERT INTO strategy_definitions (
    strategy_key,
    display_name,
    description,
    asset_class,
    segment,
    exchange,
    timeframe,
    is_active,
    max_entries_per_symbol_per_session,
    allow_reentry,
    requires_nifty_opening_session,
    created_at,
    updated_at
)
VALUES (
    'PRE_OPEN_GAP_OI',
    'Pre-Open Gap + OI Confluence',
    'NSE pre-open gap strategy confirmed by order book pressure, Nifty alignment, '
    || 'first-candle direction, and volume conviction. '
    || 'Entry: 9:16-9:18 IST. Trailing SL: breakeven at 1R, 5-min structure trail. '
    || 'Hard exit: 11:00 IST. Position sizing: B=2%, A=3%, A+=5% risk.',
    'EQUITY',
    'NSE',
    'NSE',
    '1m',
    true,
    1,       -- one entry per symbol per session (session lock)
    false,   -- no reentry after SL hit
    true,    -- requires Nifty opening session to be ready
    NOW(),
    NOW()
)
ON CONFLICT (strategy_key) DO UPDATE
    SET display_name  = EXCLUDED.display_name,
        description   = EXCLUDED.description,
        is_active     = EXCLUDED.is_active,
        updated_at    = NOW();

-- Verify
SELECT strategy_key, display_name, is_active, timeframe
FROM strategy_definitions
WHERE strategy_key = 'PRE_OPEN_GAP_OI';
