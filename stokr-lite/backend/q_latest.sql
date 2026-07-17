SELECT symbol, max(timestamp) as latest
FROM candle_data WHERE timeframe = '1min'
GROUP BY symbol
ORDER BY latest DESC
LIMIT 5;

SELECT max(timestamp) as latest_1min FROM candle_data WHERE timeframe = '1min';
