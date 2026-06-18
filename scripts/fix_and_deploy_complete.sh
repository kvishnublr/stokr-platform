#!/bin/bash
echo "=== Complete Fix and Deploy ==="
echo ""

# Fix 1: Reset PostgreSQL passwords
echo "Step 1: Resetting PostgreSQL passwords..."
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'stokr';" 2>/dev/null || echo "User stokr not found, creating..."
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr_user WITH PASSWORD 'stokr';" 2>/dev/null || echo "User stokr_user not found"
docker exec stokr-postgres psql -U postgres -c "ALTER USER postgres WITH PASSWORD 'postgres';" 2>/dev/null
echo "✓ Passwords reset"
echo ""

# Fix 2: Verify connection
echo "Step 2: Verifying database connection..."
docker exec -e PGPASSWORD=stokr stokr-postgres psql -U stokr -d stokr_platform -c "SELECT 'OK' as status;" 2>&1 | grep OK
if [ $? -eq 0 ]; then
    echo "✓ Connection verified"
else
    echo "✗ Connection failed"
    exit 1
fi
echo ""

# Fix 3: Restart API container
echo "Step 3: Restarting stokr-api..."
cd /opt/stokr-platform
docker compose restart api
echo "✓ Container restarted"
echo ""

# Fix 4: Wait for startup
echo "Step 4: Waiting 75 seconds for full startup..."
sleep 75
echo ""

# Fix 5: Check health
echo "Step 5: Checking application health..."
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 http://127.0.0.1:8080/actuator/health 2>/dev/null)
echo "Health check HTTP status: $HEALTH"

if [ "$HEALTH" = "200" ]; then
    echo "✓ Application is healthy"
else
    echo "⚠ Application not healthy yet, checking logs..."
fi
echo ""

# Fix 6: Check startup logs
echo "Step 6: Checking startup logs..."
docker logs stokr-api --tail 30 2>&1 | grep -E "Started|Tomcat started|HikariPool-1 - Started" | tail -5
echo ""

# Fix 7: Test webhook endpoint
echo "Step 7: Testing webhook endpoint..."
WEBHOOK_RESPONSE=$(curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "scan_name": "VWAP Triple Confirmation",
    "alert_name": "VWAP_TRIPLE_CONFIRMATION",
    "scan_url": "https://chartink.com/scan/test",
    "triggered_at": "12:45 PM",
    "stocks": "RELIANCE,TCS",
    "trigger_prices": "2450.50,3500.00"
  }')
echo "Webhook response: $WEBHOOK_RESPONSE"
echo ""

# Fix 8: Check webhook logs with raw payload
echo "Step 8: Checking webhook logs (should show raw payload)..."
sleep 2
docker logs stokr-api --tail 15 2>&1 | grep "chartink.webhook"
echo ""

echo "=== Deployment Complete ==="
echo ""
echo "Summary:"
docker ps | grep stokr-api
echo ""
echo "Next steps:"
echo "1. Configure Chartink Premium to send webhooks to:"
echo "   http://173.249.55.84:8080/api/chartink/webhook"
echo ""
echo "2. Use this payload format:"
echo '   {"scan_name":"...", "alert_name":"...", "triggered_at":"...", "stocks":"SYM1,SYM2", "trigger_prices":"100.5,200.3"}'
echo ""
echo "3. Monitor logs:"
echo "   docker logs stokr-api -f | grep chartink.webhook"
