SELECT timestamp, open, high, low, close, volume FROM candle_data WHERE symbol='BAJAJFINSV' AND timeframe='daily' AND timestamp BETWEEN '2026-02-25' AND '2026-03-15' ORDER BY timestamp;
