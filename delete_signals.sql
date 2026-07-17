DELETE FROM strategy_signals
WHERE deployment_id = 12
  AND DATE(created_at AT TIME ZONE 'Asia/Kolkata') = '2026-07-15';
