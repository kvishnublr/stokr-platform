#!/bin/bash
echo "=== Testing Chartink Webhook ==="

# Write test payload with proper JSON
cat > /tmp/test_webhook.json << 'EOF'
{"scan_name":"VWAP Triple Confirmation","alert_name":"VWAP_TRIPLE_CONFIRMATION","triggered_at":"12:45 PM","stocks":"RELIANCE,TCS","trigger_prices":"2450.50,3500.00"}
EOF

echo "Payload:"
cat /tmp/test_webhook.json
echo ""

echo ""
echo "Sending webhook..."
RESPONSE=$(curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d @/tmp/test_webhook.json)
echo "Response: $RESPONSE"

echo ""
echo "=== Checking logs ==="
docker logs stokr-api --since 30s 2>&1 | grep -i "chartink" | tail -10

echo ""
echo "=== Health check ==="
curl -sf --max-time 5 http://localhost:8080/actuator/health | python3 -m json.tool 2>/dev/null || curl -sf --max-time 5 http://localhost:8080/actuator/health
