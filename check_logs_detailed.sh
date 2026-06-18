#!/bin/bash
echo "=== Recent Chartink Webhook Logs (Detailed) ==="
docker logs stokr-api 2>&1 | grep -B 2 -A 5 "chartink.webhook" | tail -40

echo ""
echo "=== Last 10 minutes of logs ==="
docker logs stokr-api --since 10m 2>&1 | grep -i "chartink\|webhook\|error\|exception" | head -20
