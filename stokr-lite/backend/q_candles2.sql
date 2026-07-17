-- Symbol counts
SELECT timeframe, count(distinct symbol) as symbols, count(*) as total_candles
FROM candle_data
GROUP BY timeframe
ORDER BY timeframe;

-- Daily data: which symbols and date range
SELECT symbol, count(*) as cnt, min(timestamp) as earliest, max(timestamp) as latest
FROM candle_data WHERE timeframe = 'daily'
GROUP BY symbol
ORDER BY symbol
LIMIT 60;

-- Check for gaps in 1-min data for last 3 trading days
-- (weekends: Jul 5-6 were Sat/Sun, Jul 4 was Friday)
SELECT date(timestamp) as day, count(*) as candles
FROM candle_data WHERE timeframe = '1min'
  AND timestamp > '2026-07-01'
GROUP BY date(timestamp)
ORDER BY day;
