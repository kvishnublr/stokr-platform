#!/bin/bash
set -e

echo "=== Stokr Platform Deployment - Chartink Fix ==="
echo "Deploying to: /opt/stokr-platform"
echo "Branch: Release_v6"
echo ""

cd /opt/stokr-platform

echo "=== Pulling latest code ==="
git pull origin Release_v6

echo ""
echo "=== Building stokr-v5 API (stokr-platform monolith) ==="
docker compose build --no-cache api

echo ""
echo "=== Restarting API service ==="
docker compose up -d api

echo ""
echo "=== Waiting for startup (60 seconds) ==="
sleep 60

echo ""
echo "=== Service Status ==="
docker compose ps api

echo ""
echo "=== Health Check ==="
curl -s -o /dev/null -w "HTTP %{http_code}\n" --connect-timeout 10 http://127.0.0.1:8080/actuator/health || echo "Health check failed"

echo ""
echo "=== Application Startup ==="
docker logs stokr-api --tail 20 2>&1 | grep -E "Started|Tomcat|HikariPool"

echo ""
echo "=== Deployment Complete! ==="
echo ""
echo "Next steps:"
echo "1. Wait for next Chartink webhook"
echo "2. Check logs: docker logs stokr-api --tail 50 | grep chartink.webhook"
echo "3. Verify raw payload is logged"
echo ""
echo "=== Testing Webhook Endpoint ==="
curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "scan_name": "VWAP Triple Confirmation",
    "alert_name": "VWAP_TRIPLE_CONFIRMATION",
    "scan_url": "https://chartink.com/scan/test",
    "triggered_at": "12:30 PM",
    "stocks": "RELIANCE,TCS",
    "trigger_prices": "2450.50,3500.00"
  }'

echo ""
echo ""
echo "=== Test Webhook Logs ==="
sleep 2
docker logs stokr-api --tail 15 2>&1 | grep "chartink.webhook"
