SELECT 
    s.id,
    s.symbol,
    s.side,
    s.entry_price,
    s.stop_loss,
    s.target,
    s.exit_type,
    s.exit_price,
    s.status,
    s.confidence,
    s.reason,
    s.created_at AT TIME ZONE 'Asia/Kolkata' AS entry_time,
    s.exit_time AT TIME ZONE 'Asia/Kolkata' AS exit_time
FROM strategy_signals s
JOIN deployments d ON s.deployment_id = d.id
JOIN strategies st ON d.strategy_id = st.id
WHERE st.strategy_type = 'OVERSOLD_BOUNCE'
ORDER BY s.created_at;
