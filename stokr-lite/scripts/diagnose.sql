WITH daily_data AS (
  SELECT symbol, timestamp, open, high, low, close, volume,
    LAG(close, 1) OVER (PARTITION BY symbol ORDER BY timestamp) as prev_close,
    LAG(close, 5) OVER (PARTITION BY symbol ORDER BY timestamp) as close_5d_ago,
    LAG(close, 20) OVER (PARTITION BY symbol ORDER BY timestamp) as close_20d_ago
  FROM candle_data
  WHERE timeframe = 'daily' AND timestamp >= '2026-04-07'
),
ema50_calc AS (
  SELECT symbol, timestamp, close,
    AVG(close) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 49 PRECEDING AND CURRENT ROW) as sma50
  FROM daily_data
),
rsi_calc AS (
  SELECT d.symbol, d.timestamp, d.close,
    LAG(d.close, 1) OVER (PARTITION BY d.symbol ORDER BY d.timestamp) as prev_close
  FROM daily_data d
),
rsi_vals AS (
  SELECT symbol, timestamp, close,
    CASE
      WHEN prev_close IS NULL THEN 50
      ELSE 100 - 100.0 / (1 + (
        SELECT AVG(CASE WHEN rc2.close > rc2.prev_close THEN rc2.close - rc2.prev_close ELSE 0 END)
        FROM (SELECT rc.symbol, rc.close, rc.prev_close,
              ROW_NUMBER() OVER (ORDER BY rc.timestamp DESC) as rn
              FROM rsi_calc rc
              WHERE rc.symbol = rsi_calc.symbol AND rc.timestamp <= rsi_calc.timestamp
             ) rc2
        WHERE rc2.rn <= 14
      ) / NULLIF((
        SELECT AVG(CASE WHEN rc2.close < rc2.prev_close THEN rc2.prev_close - rc2.close ELSE 0 END)
        FROM (SELECT rc.symbol, rc.close, rc.prev_close,
              ROW_NUMBER() OVER (ORDER BY rc.timestamp DESC) as rn
              FROM rsi_calc rc
              WHERE rc.symbol = rsi_calc.symbol AND rc.timestamp <= rsi_calc.timestamp
             ) rc2
        WHERE rc2.rn <= 14
      ), 0))
    END as rsi14
  FROM daily_data d
),
final AS (
  SELECT
    e.symbol, e.timestamp, e.close, e.sma50,
    (e.close - e.sma50) / e.sma50 * 100 as dist_ema50,
    r.rsi14,
    CASE WHEN d.close < d.open THEN 'RED' ELSE 'GREEN' END as candle_color,
    d.volume,
    d.high - d.low as range_pct
  FROM ema50_calc e
  JOIN rsi_vals r ON e.symbol = r.symbol AND e.timestamp = r.timestamp
  JOIN daily_data d ON e.symbol = d.symbol AND e.timestamp = d.timestamp
)
SELECT
  'DIAGNOSTIC' as report,
  COUNT(*) as total_days,
  COUNT(*) FILTER (WHERE dist_ema50 < -3) as below_3pct,
  COUNT(*) FILTER (WHERE dist_ema50 < -4) as below_4pct,
  COUNT(*) FILTER (WHERE dist_ema50 < -5) as below_5pct,
  COUNT(*) FILTER (WHERE dist_ema50 < -7) as below_7pct,
  COUNT(*) FILTER (WHERE dist_ema50 < -10) as below_10pct,
  COUNT(*) FILTER (WHERE rsi14 < 25) as rsi_below_25,
  COUNT(*) FILTER (WHERE rsi14 < 30) as rsi_below_30,
  COUNT(*) FILTER (WHERE rsi14 < 35) as rsi_below_35,
  COUNT(*) FILTER (WHERE rsi14 < 40) as rsi_below_40,
  COUNT(*) FILTER (WHERE dist_ema50 < -3 AND rsi14 < 35) as ema3_rsi35,
  COUNT(*) FILTER (WHERE dist_ema50 < -3 AND rsi14 < 40) as ema3_rsi40,
  COUNT(*) FILTER (WHERE dist_ema50 < -2 AND rsi14 < 40) as ema2_rsi40,
  COUNT(*) FILTER (WHERE dist_ema50 < -2 AND rsi14 < 45) as ema2_rsi45
FROM final;
