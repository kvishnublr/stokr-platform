-- Was Jul 6 a holiday?
SELECT date, count(*) as cnt
FROM candle_data WHERE timeframe = 'daily' AND timestamp = '2026-07-06'
GROUP BY date;

-- Check Jul 6 in 1-min - is it truly missing?
SELECT date(timestamp) as day, count(*) 
FROM candle_data WHERE timeframe = '1min' AND date(timestamp) >= '2026-07-03'
GROUP BY day ORDER BY day;

-- What NIFTY_50 symbols are missing from 1-min (needed by OB strategy)?
SELECT symbol, count(*) as cnt, max(timestamp)
FROM candle_data WHERE timeframe = '1min' AND symbol IN (
  'RELIANCE','TCS','HDFCBANK','INFY','ICICIBANK','HINDUNILVR','ITC','SBIN',
  'BHARTIARTL','KOTAKBANK','LT','AXISBANK','ASIANPAINT','MARUTI','TITAN',
  'SUNPHARMA','BAJFINANCE','WIPRO','ULTRACEMCO','NESTLEIND','TATAMOTORS',
  'TATASTEEL','POWERGRID','NTPC','ONGC','JSWSTEEL','M&M','TATACONSUM',
  'BAJAJFINSV','ADANIENT','HCLTECH','TECHM','COALINDIA','GRASIM','DIVISLAB',
  'DRREDDY','CIPLA','BRITANNIA','HEROMOTOCO','EICHERMOT','HINDALCO','BPCL',
  'INDUSINDBK','SBILIFE','APOLLOHOSP','HDFCLIFE','UPL','ADANIPORTS','DRREDDY'
)
GROUP BY symbol
ORDER BY max(timestamp) DESC;
