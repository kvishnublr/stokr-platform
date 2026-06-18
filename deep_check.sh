#!/bin/bash
echo "=== Check Deployment Details ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT d.id, d.user_id, d.strategy_id, d.mode, d.status, d.capital, s.name as strategy_name, s.enabled as strategy_enabled FROM deployments d JOIN strategies s ON d.strategy_id = s.id WHERE d.status='ACTIVE';"

echo ""
echo "=== Check All Strategies ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, name, enabled, created_at FROM strategies;"

echo ""
echo "=== Check Market Data (NIFTY50 symbols) ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT COUNT(*) as symbol_count FROM nse_symbols;" 2>/dev/null || echo "Table nse_symbols not found"

echo ""
echo "=== Check Universe Configuration ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT * FROM app_settings WHERE key LIKE '%universe%' OR key LIKE '%symbol%';" 2>/dev/null || echo "No app_settings table or no universe config"

echo ""
echo "=== Recent Logs (Last 30 minutes) ==="
docker logs stokr-api --since 30m 2>&1 | grep -E "Starting scan|No active|Market closed|Error processing|Signal generated" | tail -20

echo ""
echo "=== Check if Scheduler is Running ==="
docker logs stokr-api 2>&1 | grep -i "scheduled\|scheduler" | head -10
