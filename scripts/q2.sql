SELECT timeframe, count(*), min(timestamp), max(timestamp) FROM candle_data WHERE symbol = 'BPCL' GROUP BY timeframe ORDER BY timeframe;
