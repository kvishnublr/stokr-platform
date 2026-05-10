-- Composite indexes for OMS read APIs and portfolio dashboards (filter/sort/pagination).

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_created ON oms_orders (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_symbol_created ON oms_orders (user_id, symbol, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_state ON oms_orders (user_id, state);

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_strategy ON oms_orders (user_id, strategy_key);

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_exec_mode ON oms_orders (user_id, execution_mode);

CREATE INDEX IF NOT EXISTS idx_oms_orders_user_backtest ON oms_orders (user_id, backtest_run_id);

CREATE INDEX IF NOT EXISTS idx_oms_executions_fill_time ON oms_executions (fill_time DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_oms_executions_exec_ts ON oms_executions (execution_timestamp DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS idx_portfolio_daily_user_date ON portfolio_daily_summary (user_id, business_date DESC);

CREATE INDEX IF NOT EXISTS idx_portfolio_pnl_user_asof ON portfolio_pnl_snapshots (user_id, as_of DESC);

CREATE INDEX IF NOT EXISTS idx_portfolio_positions_user_symbol ON portfolio_positions (user_id, symbol);
