SELECT min(timestamp), max(timestamp), count(*) FROM candle_data WHERE timeframe='1min' AND symbol='RELIANCE';
