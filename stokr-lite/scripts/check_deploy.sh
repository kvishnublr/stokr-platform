#!/bin/bash
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite << 'EOF'
-- Deployment tables
SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%deploy%' ORDER BY table_name;

-- How deployments are structured
SELECT id, status, capital, universe, strategy_ids FROM deployments ORDER BY id DESC LIMIT 5;

-- Check if signal has pnl/exit_price
SELECT column_name FROM information_schema.columns WHERE table_name='strategy_signals' ORDER BY ordinal_position;

-- How many signals per status
SELECT status, count(*) FROM strategy_signals GROUP BY status;
EOF
