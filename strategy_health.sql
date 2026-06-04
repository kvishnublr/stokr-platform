SELECT strategy_key, session_date, scan_count, signal_count,
       reject_count, last_heartbeat_at, state
FROM strategy_runtime_health
WHERE session_date = CURRENT_DATE
ORDER BY strategy_key;
