SELECT timeframe, count(*) as cnt, min(timestamp) as earliest, max(timestamp) as latest
FROM candle_data
GROUP BY timeframe
ORDER BY timeframe;
