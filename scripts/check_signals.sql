-- Check signals today
SELECT id, symbol, signal_type, entry_price, sl_price, target_price, status, created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as ist_time
FROM signals WHERE created_at::date = '2026-07-10' ORDER BY created_at DESC;

-- Check orders today
SELECT id, deployment_id, symbol, side, quantity, order_type, status, broker_order_id, created_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as ist_time
FROM orders WHERE created_at::date = '2026-07-10' ORDER BY created_at DESC;

-- Check active deployments
SELECT id, strategy_id, status, capital, started_at AT TIME ZONE 'Europe/Berlin' AT TIME ZONE 'Asia/Kolkata' as started_ist FROM deployments WHERE status IN ('LIVE','PAPER','PAUSED') ORDER BY id;

-- Check backend logs for errors today
-- SELECT * FROM application_logs WHERE created_at > NOW() - INTERVAL '1 hour' AND level = 'ERROR' ORDER BY created_at DESC LIMIT 20;
