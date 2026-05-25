#!/bin/bash
set -euo pipefail
BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
for i in $(seq 1 15); do
  if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
    echo "api_healthy"
    break
  fi
  echo "waiting_api..."
  sleep 4
done
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
echo "TOKEN_OK"

curl -sf "$BASE/api/admin/market/backfill/coverage" -H "Authorization: Bearer $TOKEN" > /tmp/coverage.json
python3 <<'PY'
import json
from datetime import datetime, timezone, timedelta
with open("/tmp/coverage.json") as f:
    payload = json.load(f)
data = payload.get("data", [])
print("coverage_rows", len(data))
cutoff = datetime.now(timezone.utc) - timedelta(days=180)
for tf in ("1m", "5m", "1d"):
    rows = [r for r in data if r.get("timeframe") == tf]
    ready = sum(1 for r in rows if r.get("completeness") == "READY")
    partial = sum(1 for r in rows if r.get("completeness") in ("PARTIAL", "GAPS_PRESENT"))
    none = sum(1 for r in rows if r.get("completeness") == "NOT_BACKFILLED")
    print(f"tf={tf} symbols={len(rows)} ready={ready} partial={partial} not_backfilled={none}")
    dated = [r for r in rows if r.get("coverageStart")]
    dated.sort(key=lambda x: x.get("coverageStart", ""))
    if dated:
        print("  oldest", dated[0].get("symbol"), dated[0].get("coverageStart"), dated[0].get("completeness"), dated[0].get("note", "")[:60])
        print("  latest_candle_sample", dated[-1].get("symbol"), dated[-1].get("latestCandleAt"), dated[-1].get("completeness"))
PY

curl -sf "$BASE/api/admin/market/backfill/capabilities" -H "Authorization: Bearer $TOKEN" > /tmp/cap.json
python3 <<'PY'
import json
with open("/tmp/cap.json") as f:
    d = json.load(f).get("data", {})
vendors = d.get("vendors") or {}
z = vendors.get("ZERODHA") or {}
print("ZERODHA_vendor", {k: z.get(k) for k in ("configured", "operationalLivePath", "operationalLivePathDetail", "tokenExpiresAt")})
PY

# DB bar counts for pilot symbols (last 180d)
docker exec stokr-postgres psql -U stokr -d stokr -t -A -c "
SELECT timeframe, COUNT(DISTINCT symbol) sym_cnt,
  MIN(open_time)::date min_dt, MAX(open_time)::date max_dt,
  COUNT(*) bar_cnt
FROM marketdata_candles
WHERE deleted = false
  AND open_time >= NOW() - INTERVAL '180 days'
  AND timeframe IN ('1m','5m','1d')
  AND symbol IN ('RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK')
GROUP BY timeframe ORDER BY timeframe;
"

docker exec stokr-postgres psql -U stokr -d stokr -t -A -c "
SELECT symbol, timeframe, COUNT(*) bars,
  MIN(open_time)::date min_dt, MAX(open_time)::date max_dt
FROM marketdata_candles
WHERE deleted = false AND open_time >= NOW() - INTERVAL '180 days'
  AND symbol IN ('RELIANCE','TCS','INFY')
  AND timeframe IN ('1m','5m','1d')
GROUP BY symbol, timeframe ORDER BY symbol, timeframe;
"

# Zerodha trader session
docker exec stokr-postgres psql -U stokr -d stokr -t -A -c "
SELECT u.principal, ba.vendor_code, ba.account_status, fs.access_token IS NOT NULL as has_token,
  fs.token_expires_at
FROM users u
JOIN broker_accounts ba ON ba.user_id = u.id AND ba.deleted = false
LEFT JOIN feed_sessions fs ON fs.vendor_code ILIKE 'ZERODHA' AND fs.deleted = false
WHERE u.principal ILIKE '%vishnu%' OR u.principal = 'vishnualgo'
LIMIT 5;
" 2>/dev/null || echo "trader_query_skipped"
