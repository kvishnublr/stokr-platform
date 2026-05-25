#!/bin/bash
set -euo pipefail
docker compose -f /opt/stokr/stokr-platform/docker-compose.yml ps postgres
docker exec stokr-postgres pg_isready -U postgres

BASE=http://127.0.0.1:8080
for i in $(seq 1 15); do curl -sf "$BASE/actuator/health" >/dev/null && break; sleep 3; done

TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START_1M="2026-03-27T04:30:00Z"
START_1D="2025-11-27T03:45:00Z"

echo "=== BEFORE ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol), MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date, COUNT(*)
FROM marketdata_candles WHERE deleted=false AND symbol IN ('RELIANCE','TCS','INFY') GROUP BY 1 ORDER BY 1;
"

create_and_wait() {
  local tf=$1 start=$2
  echo "CREATE $tf"
  job=$(curl -m 300 -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"brokerSource":"ZERODHA","symbolGroup":"NIFTY_50","timeframe":"'"$tf"'","rangeStart":"'"$start"'","rangeEnd":"'"$END"'"}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])")
  echo jobId=$job
  for i in $(seq 1 80); do
    st=$(curl -sf "$BASE/api/admin/market/backfill/jobs/$job" -H "$AUTH" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'],j.get('processedSymbols'),'/',j.get('totalSymbols'),'candles',j.get('totalCandlesFetched'),'fail',j.get('failureCount'))")
    echo "  $i $st"
    echo "$st" | grep -qE '^(COMPLETED|FAILED|CANCELLED|PARTIAL) ' && return 0
    sleep 30
  done
}

create_and_wait 1m "$START_1M"
create_and_wait 1d "$START_1D"

echo "=== AFTER ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol), MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date, COUNT(*)
FROM marketdata_candles WHERE deleted=false AND symbol IN ('RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK') GROUP BY 1 ORDER BY 1;
"
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol,timeframe,COUNT(*) bars, MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date min_d, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date max_d
FROM marketdata_candles WHERE deleted=false AND symbol='RELIANCE' GROUP BY 1,2 ORDER BY 2;
"
