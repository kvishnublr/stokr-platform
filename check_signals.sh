#!/bin/bash
# Check active deployments
echo "=== Active Deployments ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, user_id, strategy_id, mode, status, created_at FROM deployments WHERE status='ACTIVE';"

echo ""
echo "=== Signal Count Today ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT COUNT(*) as signal_count FROM strategy_signals WHERE created_at > CURRENT_DATE;"

echo ""
echo "=== Recent Signals (Last 10) ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "=== Active Strategies ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, name, symbol_type, is_enabled FROM strategies WHERE is_enabled=true;"

echo ""
echo "=== Trader Configs ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT user_id, mode, enabled, max_positions FROM trader_configs;"
