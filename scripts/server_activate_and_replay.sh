#!/bin/bash
set -euo pipefail
BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
echo "login ok"

curl -sf -X POST "$BASE/api/admin/signals/activate-pipeline?syncUniverses=true&runImmediatePoll=true" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

echo "starting NSE_SPIKE_DETECTION replay..."
curl -sf -X POST "$BASE/api/admin/signals/replay?strategyKey=NSE_SPIKE_DETECTION&from=2026-05-19&to=2026-05-23" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

sleep 45
curl -sf "$BASE/api/admin/signals/stats" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
curl -sf "$BASE/api/admin/signals?page=0&size=5&includeTestTrades=false" -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print('total', d['totalElements'])
for r in d['content']:
  print(r.get('createdAt'), r.get('strategyName'), r.get('symbol'), r.get('signalType'))
"
