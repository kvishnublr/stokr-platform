#!/bin/bash
set -x
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
curl -sv -X POST "$BASE/api/admin/market/backfill/preflight" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"NIFTY_50","timeframe":"1m","rangeStart":"2026-03-27T03:45:00Z","rangeEnd":"2026-05-22T10:00:00Z"}' 2>&1 | tail -30
