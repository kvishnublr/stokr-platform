SELECT symbol, timeframe, stale_state, replay_ready, scanner_ready,
       updated_at
FROM market_data_coverage
WHERE stale_state IS NOT NULL AND stale_state != 'OK'
ORDER BY updated_at DESC LIMIT 20;
