#!/bin/bash
set -euo pipefail
cd /opt/stokr/stokr-platform
docker compose --profile app build api 2>&1 | tail -3
docker compose --profile app up -d api
for i in $(seq 1 25); do curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 && break; sleep 5; done

BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START_1M="2026-03-27T04:30:00Z"
START_1D="2025-11-27T03:45:00Z"

echo PREFLIGHT_1M
curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"timeframe\":\"1m\",\"rangeStart\":\"$START_1M\",\"rangeEnd\":\"$END\"}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print('verdict',d['verdict'])"

for TF START in "1m $START_1M" "1d $START_1D"; do
  set -- $TF $START
  echo CREATE_$1
  RESP=$(curl -s -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"timeframe\":\"$1\",\"rangeStart\":\"$2\",\"rangeEnd\":\"$END\"}")
  echo "$RESP" | head -c 300
  JOB=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])" 2>/dev/null) || continue
  for i in $(seq 1 90); do
    J=$(curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'],j.get('processedSymbols'),j.get('totalSymbols'),j.get('totalCandlesFetched'))")
    echo poll $i $J
    echo "$J" | grep -qE '^(COMPLETED|FAILED|CANCELLED|PARTIAL)' && break
    sleep 25
  done
done

docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol), MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date, COUNT(*)
FROM marketdata_candles WHERE deleted=false AND open_time >= NOW()-INTERVAL '180 days'
 AND symbol IN ('RELIANCE','TCS','INFY') GROUP BY 1 ORDER BY 1;
"
