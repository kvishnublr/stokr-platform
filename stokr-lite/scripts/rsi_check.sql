WITH ranked AS (
  SELECT
    symbol,
    close,
    LAG(close, 1) OVER (PARTITION BY symbol ORDER BY timestamp) as prev_close,
    timestamp
  FROM candle_data
  WHERE timeframe = 'daily'
),
gains_losses AS (
  SELECT symbol, timestamp, close,
    CASE WHEN close > prev_close THEN close - prev_close ELSE 0 END as gain,
    CASE WHEN close < prev_close THEN prev_close - close ELSE 0 END as loss
  FROM ranked WHERE prev_close IS NOT NULL
),
rsi_calc AS (
  SELECT symbol, timestamp, close,
    AVG(gain) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 13 PRECEDING AND CURRENT ROW) as avg_gain,
    AVG(loss) OVER (PARTITION BY symbol ORDER BY timestamp ROWS BETWEEN 13 PRECEDING AND CURRENT ROW) as avg_loss
  FROM gains_losses
),
rsi_vals AS (
  SELECT symbol, timestamp, close,
    CASE WHEN avg_loss > 0 THEN 100 - 100/(1 + avg_gain/avg_loss) ELSE 50 END as rsi
  FROM rsi_calc
)
SELECT
  MIN(rsi)::numeric(5,1) as min_rsi,
  MAX(rsi)::numeric(5,1) as max_rsi,
  COUNT(*) FILTER (WHERE rsi < 25) as below_25,
  COUNT(*) FILTER (WHERE rsi < 30) as below_30,
  COUNT(*) FILTER (WHERE rsi < 35) as below_35,
  COUNT(*) FILTER (WHERE rsi < 40) as below_40,
  COUNT(*) as total_days
FROM rsi_vals WHERE rsi IS NOT NULL;
