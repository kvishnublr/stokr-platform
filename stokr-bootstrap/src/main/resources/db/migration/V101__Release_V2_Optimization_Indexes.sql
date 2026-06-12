-- ========================================
-- RELEASE_V2 DATABASE OPTIMIZATION
-- V101__Release_V2_Optimization_Indexes.sql
-- Creates indexes for 100-trader system
-- ========================================

-- Migration metadata
-- Author: Release_v2 Team
-- Created: 2026-06-05
-- Purpose: Optimize queries for 100 concurrent traders

-- ===== STRATEGY & SIGNAL INDEXES =====

-- Index 1: Strategy bindings active lookup (runtime_enabled instead of missing active column)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_bindings_active
  ON strategy_runtime_bindings(strategy_catalog_id)
  WHERE runtime_enabled = true;

-- Index 2: Strategy universe group lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_universe_group
  ON strategy_runtime_bindings(universe_group_id);

-- Index 3: Strategy signal user + created (for pagination)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_signal_user_created
  ON strategy_signals(user_id, created_at DESC)
  WHERE deleted = false;

-- Index 4: Strategy signal lifecycle status lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_strategy_signal_status
  ON strategy_signals(lifecycle_status)
  WHERE deleted = false;

-- ===== USER ACTIVITY INDEX (if table exists) =====
-- Skipped: user_activity_audit table does not exist in current schema

-- ===== ORDER INDEXES =====

-- Index 5: OMS order user + state (quick status checks)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_user_status
  ON oms_orders(user_id, state)
  WHERE deleted = false;

-- Index 6: OMS order created time (for latest orders queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_created_at
  ON oms_orders(created_at DESC)
  WHERE deleted = false;

-- Index 7: OMS order broker ID (for fill sync lookups)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oms_order_broker_id
  ON oms_orders(broker_order_id)
  WHERE deleted = false;

-- ===== POSITION TRACKING INDEXES =====
-- Skipped: trader_position table does not exist in current schema

-- ===== EXECUTION FILL INDEXES =====
-- Skipped: execution_fill table does not exist in current schema

-- ===== BROKER SESSION INDEXES =====
-- Skipped: broker_session table does not exist in current schema

-- ===== COMPOSITE INDEXES (COMMON QUERY PATTERNS) =====

-- Index 8: Signal pipeline lookup (user + type + date)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_signal_pipeline_lookup
  ON strategy_signals(user_id, signal_type, created_at DESC)
  WHERE deleted = false AND lifecycle_status = 'COMPLETED';

-- ===== TIME-SERIES INDEXES (BRIN) =====

-- BRIN Index for market data (efficient for time-series)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketdata_candle_time_brin
  ON marketdata_candles USING BRIN (symbol, open_time)
  WHERE deleted = false;

-- ===== FUNCTION INDEXES (CASE-INSENSITIVE EMAIL) =====

-- Index for case-insensitive email lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auth_users_email_lower
  ON auth_users(LOWER(email))
  WHERE deleted = false;

-- ===== ANALYZE TABLES AFTER INDEX CREATION =====

-- Update statistics for optimizer
ANALYZE strategy_runtime_bindings;
ANALYZE strategy_signals;
ANALYZE oms_orders;
ANALYZE marketdata_candles;
ANALYZE auth_users;
