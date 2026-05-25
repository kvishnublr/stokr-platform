#!/bin/bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
echo preflight...
time curl -m 120 -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"CUSTOM","customSymbols":["RELIANCE"],"timeframe":"1m","rangeStart":"2026-03-27T04:30:00Z","rangeEnd":"2026-05-22T10:00:00Z"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['verdict'])"

echo create_job...
time curl -m 120 -v -X POST "$BASE/api/admin/market/backfill/jobs" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"CUSTOM","customSymbols":["RELIANCE"],"timeframe":"1m","rangeStart":"2026-03-27T04:30:00Z","rangeEnd":"2026-05-22T10:00:00Z"}' 2>&1 | tail -20
