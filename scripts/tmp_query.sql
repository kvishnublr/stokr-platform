SELECT symbol, status, COUNT(*) as cnt FROM strategy_signals WHERE created_at >= '2026-07-14' GROUP BY symbol, status ORDER BY cnt DESC LIMIT 30;
