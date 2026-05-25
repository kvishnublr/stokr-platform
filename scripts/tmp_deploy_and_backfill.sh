#!/bin/bash
set -euo pipefail
cd /opt/stokr/stokr-platform

echo "=== BUILD API ==="
docker compose --profile app build api 2>&1 | tail -5
docker compose --profile app up -d api
for i in $(seq 1 20); do
  curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 && echo api_ready && break
  sleep 6
done

BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START_1M="2026-03-27T04:30:00Z"
START_1D="2025-11-27T03:45:00Z"

echo "=== PREFLIGHT 1m (trader token) ==="
curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"timeframe\":\"1m\",\"rangeStart\":\"$START_1M\",\"rangeEnd\":\"$END\"}" \
  | python3 -m json.tool | head -25

create_job() {
  local TF="$1" START="$2"
  echo "=== CREATE $TF JOB ==="
  JOB=$(curl -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"timeframe\":\"$TF\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])")
  echo "jobId=$JOB"
  for i in $(seq 1 80); do
    DET=$(curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH")
    echo "$DET" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(i, j['status'], j.get('processedSymbols'),'/',j.get('totalSymbols'),'candles',j.get('totalCandlesFetched'),j.get('message','')[:60])" 2>/dev/null || true
    ST=$(echo "$DET" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['job']['status'])")
    case "$ST" in COMPLETED|FAILED|CANCELLED|PARTIAL) break;; esac
    sleep 20
  done
}

create_job 1m "$START_1M"
create_job 1d "$START_1D"

echo "=== DB AFTER ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol) sym_cnt,
  MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_ist,
  MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_ist,
  COUNT(*) bars
FROM marketdata_candles
WHERE deleted = false AND open_time >= NOW() - INTERVAL '180 days'
  AND symbol IN ('RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK')
GROUP BY timeframe ORDER BY timeframe;
"
