-- NIFTY_50 symbols with gaps (expected ~375 candles per trading day)
-- Check which NIFTY_50 symbols have fewer candles than expected
WITH nifty50 AS (
  SELECT DISTINCT symbol FROM candle_data WHERE timeframe = '1min' 
    AND symbol IN ('RELIANCE','TCS','HDFCBANK','INFY','ICICIBANK','HINDUNILVR','ITC','SBIN',
    'BHARTIARTL','KOTAKBANK','LT','AXISBANK','ASIANPAINT','MARUTI','TITAN',
    'SUNPHARMA','BAJFINANCE','WIPRO','ULTRACEMCO','NESTLEIND','TATAMOTORS',
    'TATASTEEL','POWERGRID','NTPC','ONGC','JSWSTEEL','M&M','TATACONSUM',
    'BAJAJFINSV','ADANIENT','HCLTECH','TECHM','COALINDIA','GRASIM','DIVISLAB',
    'DRREDDY','CIPLA','BRITANNIA','HEROMOTOCO','EICHERMOT','HINDALCO','BPCL',
    'INDUSINDBK','SBILIFE','APOLLOHOSP','HDFCLIFE','UPL','ADANIPORTS','TRENT')
)
SELECT c.symbol, count(*) as total_candles, 
       count(DISTINCT date(c.timestamp)) as trading_days,
       min(c.max_ts) as last_candle
FROM nifty50 n
JOIN LATERAL (
  SELECT symbol, max(timestamp) as max_ts
  FROM candle_data 
  WHERE timeframe = '1min' AND symbol = n.symbol
  GROUP BY symbol
) c ON true
JOIN candle_data c2 ON c2.symbol = n.symbol AND c2.timeframe = '1min'
GROUP BY c.symbol, c.max_ts
HAVING count(*) < 6000
ORDER BY total_candles;

-- Simpler: just check total count per NIFTY_50 symbol
SELECT symbol, count(*) as cnt, max(timestamp) as latest
FROM candle_data WHERE timeframe = '1min' AND symbol IN (
  'RELIANCE','TCS','HDFCBANK','INFY','ICICIBANK','HINDUNILVR','ITC','SBIN',
  'BHARTIARTL','KOTAKBANK','LT','AXISBANK','ASIANPAINT','MARUTI','TITAN',
  'SUNPHARMA','BAJFINANCE','WIPRO','ULTRACEMCO','NESTLEIND','TATAMOTORS',
  'TATASTEEL','POWERGRID','NTPC','ONGC','JSWSTEEL','M&M','TATACONSUM',
  'BAJAJFINSV','ADANIENT','HCLTECH','TECHM','COALINDIA','GRASIM','DIVISLAB',
  'DRREDDY','CIPLA','BRITANNIA','HEROMOTOCO','EICHERMOT','HINDALCO','BPCL',
  'INDUSINDBK','SBILIFE','APOLLOHOSP','HDFCLIFE','UPL','ADANIPORTS','TRENT'
)
GROUP BY symbol
ORDER BY cnt ASC
LIMIT 10;
