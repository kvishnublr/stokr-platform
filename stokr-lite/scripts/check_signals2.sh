#!/bin/bash
set -e
echo "=== Sample signals (all cols) ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, symbol, side, entry_price, stop_loss, target, status, exit_type, strategy_id, entry_time, exit_time, movement_score, signal_source FROM strategy_signals ORDER BY id DESC LIMIT 5;"
echo ""
echo "=== Strategies with id/name ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, name FROM strategies ORDER BY id;"
echo ""
echo "=== Deployment strategies ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT ds.id, ds.strategy_id, s.name, ds.status, ds.capital, ds.universe FROM deployment_strategies ds JOIN strategies s ON ds.strategy_id = s.id ORDER BY ds.id;"
echo ""
echo "=== Any existing pnl/exit_price columns? ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT column_name FROM information_schema.columns WHERE table_name='strategy_signals' AND column_name IN ('pnl','exit_price','profit_loss','pnl_pct');"
echo ""
echo "=== metadata_json sample ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, metadata_json FROM strategy_signals ORDER BY id DESC LIMIT 3;"

