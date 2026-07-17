-- Is Jul 6 in daily data?
SELECT symbol, timestamp, open, high, low, close, volume
FROM candle_data WHERE timeframe = 'daily' AND timestamp = '2026-07-06'
LIMIT 5;

-- How many NIFTY_50 symbols have daily for Jul 6?
SELECT count(*) FROM candle_data WHERE timeframe = 'daily' AND timestamp = '2026-07-06';

-- Exact gap analysis: which trading days are missing in 1-min for each NIFTY_50 symbol?
-- First find which days had trading (from daily data)
SELECT DISTINCT timestamp::date as trading_day FROM candle_data WHERE timeframe = 'daily' AND timestamp >= '2026-06-08' ORDER BY trading_day;
