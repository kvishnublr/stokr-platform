-- Verify Jul 6 now has candles
SELECT date(timestamp) as day, count(*)
FROM candle_data WHERE timeframe = '1min' AND date(timestamp) >= '2026-07-03'
GROUP BY day ORDER BY day;

-- Verify NIFTY_50 key symbols now have Jul 6 data
SELECT symbol, count(*), min(timestamp), max(timestamp)
FROM candle_data WHERE timeframe = '1min' AND symbol IN ('M&M','TATAMOTORS','RELIANCE','TCS','HDFCBANK','INFY')
  AND date(timestamp) = '2026-07-06'
GROUP BY symbol ORDER BY symbol;

-- Check M&M and TATAMOTORS full coverage now
SELECT symbol, count(*), min(timestamp), max(timestamp)
FROM candle_data WHERE timeframe = '1min' AND symbol IN ('M&M','TATAMOTORS')
GROUP BY symbol;
