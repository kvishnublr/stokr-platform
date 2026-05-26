SELECT g.group_key, s.symbol
FROM strategy_universe_groups g
JOIN strategy_universe_symbols s ON s.group_id=g.id AND s.enabled
WHERE g.group_key IN ('NIFTY_50','NIFTY_100') AND s.symbol IN ('TATAMOTORS','RELIANCE','INFY')
ORDER BY 1,2;

SELECT symbol,
  MIN((open_time AT TIME ZONE 'Asia/Kolkata')::date) AS first_1m,
  MAX((open_time AT TIME ZONE 'Asia/Kolkata')::date) AS last_1m,
  COUNT(*) AS bars
FROM marketdata_candles
WHERE deleted=false AND timeframe='1m'
  AND symbol IN (SELECT s.symbol FROM strategy_universe_groups g JOIN strategy_universe_symbols s ON s.group_id=g.id AND s.enabled WHERE g.group_key='NIFTY_100')
GROUP BY symbol
HAVING MIN((open_time AT TIME ZONE 'Asia/Kolkata')::date) >= '2026-05-18'
ORDER BY bars DESC
LIMIT 15;

SELECT COUNT(*) AS nifty100_symbols_deep_before_may18
FROM (
  SELECT s.symbol
  FROM strategy_universe_groups g
  JOIN strategy_universe_symbols s ON s.group_id=g.id AND s.enabled
  WHERE g.group_key='NIFTY_100'
) e
JOIN marketdata_candles c ON c.symbol=e.symbol AND c.timeframe='1m' AND c.deleted=false
GROUP BY e.symbol
HAVING MIN((c.open_time AT TIME ZONE 'Asia/Kolkata')::date) < '2026-05-18';
