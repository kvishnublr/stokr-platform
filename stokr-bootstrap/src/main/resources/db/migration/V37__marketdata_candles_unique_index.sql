-- Ensure candle upsert conflict target exists for:
-- ON CONFLICT (symbol, timeframe, open_time)

-- Safety: remove accidental duplicates first (if any).
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY symbol, timeframe, open_time
               ORDER BY updated_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
           ) AS rn
    FROM marketdata_candles
)
DELETE FROM marketdata_candles
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- Required unique index for candle upsert path.
CREATE UNIQUE INDEX IF NOT EXISTS ux_marketdata_candles_symbol_timeframe_open_time
    ON marketdata_candles(symbol, timeframe, open_time);
