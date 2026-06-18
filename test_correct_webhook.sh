#!/bin/bash
echo "=== Testing CORRECT Chartink Webhook Endpoint ==="
echo ""

echo "1. Sending test webhook with proper format..."
curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "scan_name": "VWAP Triple Confirmation",
    "alert_name": "VWAP_TRIPLE_CONFIRMATION",
    "scan_url": "https://chartink.com/scan/test",
    "triggered_at": "12:00 PM",
    "stocks": "RELIANCE,TCS,INFY",
    "trigger_prices": "2450.50,3500.00,1500.00"
  }' | python3 -m json.tool

echo ""
echo "2. Checking logs..."
sleep 2
docker logs stokr-api --tail 20 2>&1 | grep "chartink.webhook"

echo ""
echo "3. Checking if alerts were stored..."
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT symbol, scan_name, trigger_price, triggered_at FROM chartink_alerts ORDER BY triggered_at DESC LIMIT 5;" 2>/dev/null || echo "Table not found - checking strategy_signals..."
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, symbol, side, status, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 5;"
