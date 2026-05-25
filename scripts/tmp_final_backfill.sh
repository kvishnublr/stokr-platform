#!/bin/bash
set -euo pipefail
cd /opt/stokr/stokr-platform
docker compose --profile app build api 2>&1 | tail -2
docker compose --profile app up -d api
for i in $(seq 1 30); do curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 && break; sleep 5; done

BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START_1M="2026-03-27T04:30:00Z"
START_1D="2025-11-27T03:45:00Z"

curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"CUSTOM","customSymbols":["RELIANCE"],"timeframe":"1m","rangeStart":"'"$START_1M"'","rangeEnd":"'"$END"'"}' \
  | python3 -c "import sys,json; print('preflight_1m',json.load(sys.stdin)['data']['verdict'])"

run_one() {
  local tf=$1 start=$2
  echo "=== JOB $tf ==="
  job=$(curl -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"brokerSource":"ZERODHA","symbolGroup":"NIFTY_50","timeframe":"'"$tf"'","rangeStart":"'"$start"'","rangeEnd":"'"$END"'"}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])")
  echo jobId=$job
  for i in $(seq 1 100); do
    st=$(curl -sf "$BASE/api/admin/market/backfill/jobs/$job" -H "$AUTH" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'],j.get('processedSymbols'),j.get('totalSymbols'),j.get('totalCandlesFetched'),j.get('failureCount'))")
    echo "poll$i $st"
    echo "$st" | grep -qE '^(COMPLETED|FAILED|CANCELLED|PARTIAL) ' && break
    sleep 20
  done
}

run_one 1m "$START_1M"
run_one 1d "$START_1D"

echo "=== COVERAGE API (pilot) ==="
curl -sf "$BASE/api/admin/market/backfill/coverage" -H "$AUTH" | python3 <<'PY'
import sys,json
data=json.load(sys.stdin).get("data",[])
for sym in ["RELIANCE","TCS","INFY"]:
  for tf in ["1m","5m","1d"]:
    rows=[r for r in data if r.get("symbol")==sym and r.get("timeframe")==tf]
    if rows:
      r=rows[0]
      print(sym,tf,r.get("coverageStart"),r.get("coverageEnd"),r.get("completeness"),r.get("note","")[:50])
PY

echo "=== DB COUNTS ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol) syms, MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_d, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_d, COUNT(*) bars
FROM marketdata_candles WHERE deleted=false AND open_time >= NOW()-INTERVAL '180 days'
 AND symbol IN ('RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK') GROUP BY 1 ORDER BY 1;
"
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT COUNT(DISTINCT symbol) nifty50_syms FROM marketdata_candles c
WHERE deleted=false AND timeframe='1m' AND open_time >= NOW()-INTERVAL '180 days'
 AND symbol IN (SELECT DISTINCT symbol FROM marketdata_candles WHERE symbol='RELIANCE' OR symbol='TCS');
"
