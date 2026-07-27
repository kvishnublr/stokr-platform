#!/bin/bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@stokr.in","password":"`$ADMIN_PASSWORD"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
echo "Token: ${TOKEN:0:30}..."
curl -s -X POST http://localhost:8081/api/orders/manual \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"symbol":"RELIANCE","side":"BUY","quantity":1,"orderType":"MARKET","mode":"PAPER"}'
echo ""
curl -s -X POST http://localhost:8081/api/orders/manual \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"symbol":"TCS","side":"BUY","quantity":2,"orderType":"MARKET","mode":"PAPER"}'
echo ""

