SELECT
  'EMA50 DISTANCE DISTRIBUTION (Apr 7 - Jul 7, 2026)' as report,
  COUNT(*) as total_days_x_symbols,
  COUNT(*) FILTER (WHERE dist < -2) as below_2pct,
  COUNT(*) FILTER (WHERE dist < -3) as below_3pct,
  COUNT(*) FILTER (WHERE dist < -4) as below_4pct,
  COUNT(*) FILTER (WHERE dist < -5) as below_5pct,
  COUNT(*) FILTER (WHERE dist < -7) as below_7pct
FROM (
  SELECT symbol, timestamp, close,
    AVG(close) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 49 PRECEDING AND CURRENT ROW) as sma50,
    (close - AVG(close) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 49 PRECEDING AND CURRENT ROW)) /
      NULLIF(AVG(close) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 49 PRECEDING AND CURRENT ROW), 0) * 100 as dist
  FROM candle_data
  WHERE timeframe = 'daily' AND timestamp >= '2026-04-07'
) sub
WHERE dist IS NOT NULL;
