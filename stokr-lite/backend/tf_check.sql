SELECT timeframe, count(*) as cnt, min(timestamp), max(timestamp)
FROM candle_data
WHERE timeframe IN ('1min', '5min', '15min', '30min', 'daily')
GROUP BY timeframe ORDER BY timeframe;
