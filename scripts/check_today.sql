-- Check signals today (IST)
SELECT ss.id, ss.symbol, ss.side, ss.entry_price, ss.stop_loss, ss.target, ss.status, ss.confidence, ss.reason, ss.scanner_name,
       ss.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as ist_time
FROM strategy_signals ss
WHERE ss.created_at::date = '2026-07-10' OR ss.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' >= '2026-07-10 00:00:00+05:30'
ORDER BY ss.created_at DESC;

-- Check signals this week
SELECT ss.id, ss.symbol, ss.side, ss.entry_price, ss.status, ss.scanner_name,
       ss.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as ist_time
FROM strategy_signals ss
WHERE ss.created_at > NOW() - INTERVAL '7 days'
ORDER BY ss.created_at DESC LIMIT 20;

-- Check orders
SELECT o.id, o.deployment_id, o.symbol, o.side, o.quantity, o.price, o.order_type, o.status, o.broker_order_id,
       o.created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as ist_time
FROM orders o
WHERE o.created_at > NOW() - INTERVAL '7 days'
ORDER BY o.created_at DESC LIMIT 20;

-- Check active deployments
SELECT d.id, d.strategy_id, s.name as strategy_name, d.status, d.capital, d.mode
FROM deployments d
JOIN strategies s ON d.strategy_id = s.id
WHERE d.status IN ('LIVE','PAPER','PAUSED') ORDER BY d.id;

-- Check backend container logs for today
