-- Disable all strategies except the two proven profitable ones
UPDATE strategies SET enabled = false
WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE');

-- Ensure our two profitable strategies are enabled
UPDATE strategies SET enabled = true
WHERE strategy_type IN ('MORNING_SURGE_REVERSAL', 'VWAP_REVERSION');

-- Remove universe mappings for disabled strategies
DELETE FROM strategy_universe_mappings
WHERE strategy_id IN (
  SELECT id FROM strategies WHERE strategy_type IN ('PRE_OPEN', 'TRADE_BOOK_IMBALANCE', 'VWAP_TRIPLE', 'ORB_V', 'MORNING_SURGE')
);
