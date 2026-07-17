-- Which symbols have 1-min data
SELECT symbol, count(*) as cnt, min(timestamp), max(timestamp)
FROM candle_data WHERE timeframe = '1min'
GROUP BY symbol
ORDER BY max(timestamp) DESC
LIMIT 20;
