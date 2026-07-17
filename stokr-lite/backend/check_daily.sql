SELECT symbol, timestamp, open, high, low, close, volume FROM candle_data WHERE symbol='RELIANCE' AND timeframe='daily' ORDER BY timestamp ASC LIMIT 5;
