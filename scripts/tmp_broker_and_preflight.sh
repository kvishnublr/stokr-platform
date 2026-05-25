#!/bin/bash
set -euo pipefail
BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"

echo "=== BROKER INFRASTRUCTURE ==="
curl -sf "$BASE/api/admin/broker-infrastructure" -H "$AUTH" | python3 -m json.tool 2>/dev/null | head -80 || \
curl -sf "$BASE/api/admin/broker/infrastructure" -H "$AUTH" | python3 -m json.tool 2>/dev/null | head -80 || echo "broker_api_not_found"

echo "=== PLATFORM READINESS ==="
curl -sf "$BASE/api/admin/readiness" -H "$AUTH" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); print('blocking',d.get('blocking')); print('summary',str(d)[:400])" || echo "readiness_skip"

# IST windows: end = last Friday 15:30 if weekend; use explicit UTC instants
# 2026-05-23 15:30 IST = 2026-05-23T10:00:00Z
END_IST="2026-05-23T10:00:00Z"
START_60D="2026-03-24T03:45:00Z"   # 2026-03-24 09:15 IST
START_180D="2025-11-26T03:45:00Z"  # ~180d before May 23

for TF in 1m 1d; do
  if [ "$TF" = "1m" ]; then START="$START_60D"; else START="$START_180D"; fi
  echo "=== PREFLIGHT $TF NIFTY_50 ZERODHA ==="
  curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"customSymbols\":null,\"timeframe\":\"$TF\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END_IST\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print('verdict',d.get('verdict')); print('blockers',d.get('blockers')); print('warnings',d.get('warnings')); print('symbolCount',d.get('symbolCount'))"
done
