SELECT 
    s.id,
    s.symbol,
    s.side,
    s.entry_price,
    s.stop_loss,
    s.target,
    s.status,
    s.exit_type,
    s.entry_time AT TIME ZONE 'Asia/Kolkata' AS entry_time,
    s.exit_time AT TIME ZONE 'Asia/Kolkata' AS exit_time,
    s.confidence,
    s.reason
FROM strategy_signals s
WHERE s.deployment_id = 11
ORDER BY s.created_at;
