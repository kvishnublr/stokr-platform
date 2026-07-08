SELECT MIN(timestamp)::date as earliest, MAX(timestamp)::date as latest, COUNT(*) as total FROM candle_data WHERE timeframe='daily';
