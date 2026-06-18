#!/bin/bash
echo "=== Testing webhook ==="
docker exec stokr-api curl -s -X POST http://localhost:8080/api/chartink/webhook \
  -H "Content-Type: application/json" \
  -d '{"strategy":"VWAP_TRIPLE_CONFIRMATION","symbol":"NIFTY","action":"buy"}' 2>&1

echo ""
echo "=== Testing dashboard ==="
docker exec stokr-api curl -s http://localhost:8080/api/v1/adv-dashboard/dashboard-metrics 2>&1 | head -50

echo ""
echo "=== Testing auth endpoints ==="
docker exec stokr-api curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"test"}' 2>&1 | head -20
