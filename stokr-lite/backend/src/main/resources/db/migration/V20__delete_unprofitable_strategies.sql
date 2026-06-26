-- Remove universe mappings first (FK constraint)
DELETE FROM strategy_universe_mappings
WHERE strategy_id IN (
  SELECT id FROM strategies
  WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE')
);

-- Delete unprofitable strategies
DELETE FROM strategies
WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE');
