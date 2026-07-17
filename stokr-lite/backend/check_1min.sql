SELECT count(*) as total_candles, count(distinct symbol) as symbols,
       min(timestamp) as earliest, max(timestamp) as latest
FROM candles_1min;
