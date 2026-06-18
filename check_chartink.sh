#!/bin/bash
echo "=== Chartink Webhook Signals (Last 10) ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT id, scanner_name, symbol, side, chartink_score, created_at FROM chartink_signals ORDER BY created_at DESC LIMIT 10;"

echo ""
echo "=== Chartink Signals Today ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT COUNT(*) as total, EXTRACT(HOUR FROM created_at) as hour FROM chartink_signals WHERE created_at > CURRENT_DATE GROUP BY EXTRACT(HOUR FROM created_at) ORDER BY hour;"

echo ""
echo "=== Confidence Scores (Recent) ==="
docker exec stokr-postgres psql -U postgres -d stokr_lite -c "SELECT symbol, confidence_score, created_at FROM confidence_scores ORDER BY created_at DESC LIMIT 10;" 2>/dev/null || echo "Table not found"

echo ""
echo "=== Check Webhook Endpoint ==="
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" http://localhost:8080/api/webhooks/chartink

echo ""
echo "=== Market Data Status ==="
docker logs stokr-api 2>&1 | grep -i "market.*open\|market.*close\|NseTick\|price.*update" | tail -10

echo ""
echo "=== Chartink Schedule Service Running? ==="
docker logs stokr-api 2>&1 | grep "ChartinkScheduleService\|monitorPositions" | tail -5
