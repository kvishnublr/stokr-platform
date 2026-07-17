-- Check NIFTY_50 universe for 1-min: which symbols are partial/missing for Jun 8 to Jul 7?
SELECT symbol, count(*) as cnt, min(timestamp) as earliest, max(timestamp) as latest
FROM candle_data WHERE timeframe = '1min' AND timestamp >= '2026-06-08'
GROUP BY symbol
ORDER BY symbol;

-- Check specifically Jul 6 (should be a trading day)
SELECT symbol, count(*) as jul6_candles
FROM candle_data WHERE timeframe = '1min' AND date(timestamp) = '2026-07-06'
GROUP BY symbol;

-- Check Jul 3 
SELECT symbol, count(*) as jul3_candles
FROM candle_data WHERE timeframe = '1min' AND date(timestamp) = '2026-07-03'
GROUP BY symbol
HAVING count(*) < 300;
