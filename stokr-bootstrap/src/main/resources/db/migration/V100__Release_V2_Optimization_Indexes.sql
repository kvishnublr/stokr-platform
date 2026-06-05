-- ========================================
-- RELEASE_V2 DATABASE OPTIMIZATION
-- V100__Release_V2_Optimization_Indexes.sql
-- Creates 15 new indexes for 100-trader system
-- ========================================

-- Migration metadata
-- Author: Release_v2 Team
-- Created: 2026-06-05
-- Purpose: Optimize queries for 100 concurrent traders
-- Risk: LOW (CONCURRENTLY flag prevents locks)

-- ===== STRATEGY & SIGNAL INDEXES =====

-- Index 1: Strategy bindings active lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_bindings_active
  ON strategy_runtime_bindings(strategy_catalog_id, active)
  WHERE active = true AND deleted = false;

-- Index 2: Strategy universe group lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_universe_group
  ON strategy_runtime_bindings(universe_group_id)
  WHERE deleted = false;

-- Index 3: Strategy signal user + created (for pagination)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_signal_user_created
  ON strategy_signal(user_id, created_at DESC)
  WHERE deleted = false;

-- Index 4: Strategy signal status lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_signal_status
  ON strategy_signal(signal_status)
  WHERE deleted = false;

-- ===== USER & ORDER INDEXES =====

-- Index 5: User activity audit lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_activity_user_created
  ON user_activity_audit(user_id, created_at DESC)
  WHERE deleted = false;

-- Index 6: OMS order user + status (quick status checks)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_user_status
  ON oms_order(user_id, order_status)
  WHERE deleted = false;

-- Index 7: OMS order created time (for latest orders queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_created_at
  ON oms_order(created_at DESC)
  WHERE deleted = false;

-- Index 8: OMS order broker ID (for fill sync lookups)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_broker_id
  ON oms_order(broker_id)
  WHERE deleted = false;

-- ===== POSITION TRACKING INDEXES =====

-- Index 9: Position user + symbol (quick position lookup)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trader_position_user_symbol
  ON trader_position(user_id, symbol)
  WHERE closed_at IS NULL;

-- Index 10: Position user + strategy (strategy-specific positions)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trader_position_user_strategy
  ON trader_position(user_id, strategy_key)
  WHERE closed_at IS NULL;

-- ===== EXECUTION FILL INDEXES =====

-- Index 11: Execution fill user + order (fill lookup)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_execution_fill_user_order
  ON execution_fill(user_id, order_id)
  WHERE deleted = false;

-- Index 12: Execution fill created time (audit trail)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_execution_fill_created
  ON execution_fill(created_at DESC)
  WHERE deleted = false;

-- ===== BROKER SESSION INDEXES =====

-- Index 13: Broker session user + active (connection check)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_broker_session_user_active
  ON broker_session(user_id, is_active)
  WHERE deleted = false;

-- ===== COMPOSITE INDEXES (COMMON QUERY PATTERNS) =====

-- Index 14: Signal pipeline lookup (user + strategy + date)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_signal_pipeline_lookup
  ON strategy_signal(user_id, strategy_key, created_at DESC)
  WHERE deleted = false AND signal_status = 'COMPLETED';

-- Index 15: Position summary (user + strategy + closed)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_position_summary
  ON trader_position(user_id, strategy_key, closed_at)
  WHERE deleted = false;

-- ===== TIME-SERIES INDEXES (BRIN - more efficient than B-tree for large time series) =====

-- BRIN Index for market data (more efficient than B-tree for time-series)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketdata_candle_time_brin
  ON market_data_candle USING BRIN (symbol, candle_time)
  WHERE deleted = false;

-- ===== FUNCTION INDEXES (CASE-INSENSITIVE EMAIL) =====

-- Index for case-insensitive email lookup (if not already exists)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auth_users_email_lower
  ON auth_users(LOWER(email))
  WHERE deleted = false;

-- ===== ANALYZE TABLES AFTER INDEX CREATION =====

-- Update statistics for optimizer
ANALYZE strategy_runtime_bindings;
ANALYZE strategy_signal;
ANALYZE oms_order;
ANALYZE trader_position;
ANALYZE execution_fill;
ANALYZE broker_session;
ANALYZE auth_users;
ANALYZE market_data_candle;

-- ===== POST-MIGRATION VERIFICATION =====

-- These queries verify indexes are created (run manually if needed):
-- SELECT schemaname, tablename, indexname FROM pg_indexes
-- WHERE schemaname = 'public' AND indexname LIKE 'idx_%'
-- ORDER BY indexname;

-- Verify replication lag is acceptable:
-- SELECT slot_name, restart_lsn FROM pg_replication_slots;

-- Check for unused indexes (run after 24h):
-- SELECT schemaname, tablename, indexname, idx_scan FROM pg_stat_user_indexes
-- ORDER BY idx_scan ASC;

