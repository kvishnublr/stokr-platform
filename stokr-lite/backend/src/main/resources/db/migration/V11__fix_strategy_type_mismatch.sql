-- Fix strategy_type mismatch for id=2.
-- V10__seed_chartink_strategies.sql set strategy_type='TRADE_BOOK' but
-- TradeBookImbalanceStrategy.getStrategyType() returns 'TRADE_BOOK_IMBALANCE'.
-- Without this fix, StrategyService.getPlugin('TRADE_BOOK') throws
-- "No plugin registered for strategy type: TRADE_BOOK".
UPDATE strategies SET strategy_type = 'TRADE_BOOK_IMBALANCE' WHERE id = 2;
