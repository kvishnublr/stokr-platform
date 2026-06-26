-- Delete dependent records referencing strategies to be removed
DELETE FROM strategy_signals
WHERE strategy_id IN (
  SELECT id FROM strategies
  WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE')
);

DELETE FROM deployments
WHERE strategy_id IN (
  SELECT id FROM strategies
  WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE')
);

-- Delete unprofitable strategies (CASCADE handles strategy_configs, strategy_universe_mappings)
DELETE FROM strategies
WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE');
