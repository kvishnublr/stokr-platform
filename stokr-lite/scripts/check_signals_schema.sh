#!/bin/bash
set -e
echo "=== strategy_signals columns ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='strategy_signals' ORDER BY ordinal_position;"
echo ""
echo "=== Sample signals ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, entry_price, stop_loss, target, status, exit_type, pnl, ltp, strategy_name, created_at FROM strategy_signals ORDER BY id DESC LIMIT 5;"
echo ""
echo "=== Check if ltp column exists ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT column_name FROM information_schema.columns WHERE table_name='strategy_signals' AND column_name IN ('ltp', 'current_price', 'exit_price', 'pnl', 'profit_loss', 'strategy_type', 'timeframe');"
echo ""
echo "=== Strategies table - check for timeframe/type column ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT column_name FROM information_schema.columns WHERE table_name='strategies';"
echo ""
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, name, timeframe FROM strategies ORDER BY id;"

