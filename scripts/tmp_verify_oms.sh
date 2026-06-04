#!/bin/bash
sleep 90
echo "health=$(docker inspect -f '{{.State.Health.Status}}' stokr-api)"
TOKEN=$(curl -sf -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin","password":"password"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
H="Authorization: Bearer $TOKEN"
echo "=== summary ==="
curl -sf -H "$H" http://localhost:8080/api/admin/oms/summary | python3 -m json.tool
echo "=== reject-reasons ==="
curl -sf -H "$H" http://localhost:8080/api/admin/oms/reject-reasons | python3 -m json.tool
