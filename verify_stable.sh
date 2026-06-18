#!/bin/bash
echo "=== Restarting API container ==="
docker restart stokr-api

echo "Waiting for startup..."
sleep 90

echo "=== Container status ==="
docker ps --filter name=stokr-api --format '{{.Status}}'

echo ""
echo "=== Test catalog ==="
docker exec stokr-api curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8080/api/strategies/catalog

echo ""
echo "=== Test webhook ==="
docker exec stokr-api curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{"strategy":"VWAP_TRIPLE_CONFIRMATION","symbol":"NIFTY","action":"buy"}'

echo ""
echo "=== 30s later status ==="
sleep 30
docker ps --filter name=stokr-api --format '{{.Status}}'
echo "Still running if status shows Up X minutes"

echo ""
echo "=== 60s more status ==="
sleep 60
docker ps --filter name=stokr-api --format '{{.Status}}'
echo "Still running if status shows Up X minutes"
