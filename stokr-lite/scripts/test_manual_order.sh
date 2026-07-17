#!/bin/bash
# Login and get JWT token
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@stokr.in","password":"Temp@12345678"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

echo "Token: ${TOKEN:0:30}..."

# Place a PAPER order
curl -s -X POST http://localhost:8081/api/orders/manual \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"symbol":"RELIANCE","side":"BUY","quantity":1,"orderType":"MARKET","mode":"PAPER"}' | python3 -m json.tool
