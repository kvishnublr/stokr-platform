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
    s.created_at AT TIME ZONE 'Asia/Kolkata' AS entry_time,
    s.exit_time AT TIME ZONE 'Asia/Kolkata' AS exit_time,
    CASE 
        WHEN s.exit_price IS NOT NULL AND s.exit_price > 0 AND s.entry_price > 0 THEN
            CASE 
                WHEN s.side = 'LONG' THEN 
                    ROUND(((s.exit_price - s.entry_price) / s.entry_price * 100)::numeric, 2)
                ELSE 
                    ROUND(((s.entry_price - s.exit_price) / s.entry_price * 100)::numeric, 2)
            END
        ELSE NULL
    END AS pnl_pct,
    CASE 
        WHEN s.exit_price IS NOT NULL AND s.exit_price > 0 AND s.entry_price > 0 THEN
            CASE 
                WHEN s.side = 'LONG' THEN 
                    ROUND((s.exit_price - s.entry_price)::numeric, 2)
                ELSE 
                    ROUND((s.entry_price - s.exit_price)::numeric, 2)
            END
        ELSE NULL
    END AS pnl_per_share
FROM signals s
JOIN deployments d ON s.deployment_id = d.id
JOIN strategies st ON d.strategy_id = st.id
WHERE st.strategy_type = 'OVERSOLD_BOUNCE'
ORDER BY s.created_at;
