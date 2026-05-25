#!/bin/bash
set -euo pipefail
BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"

# Last NSE session: Fri 2026-05-22 15:30 IST = 2026-05-22T10:00:00Z
END="2026-05-22T10:00:00Z"
START_1M="2026-03-27T03:45:00Z"    # Fri 2026-03-27 09:15 IST (~57d, within Zerodha 60d)
START_1D="2025-11-27T03:45:00Z"    # Thu 2025-11-27 09:15 IST (~177d)
START_5M="2026-03-27T03:45:00Z"

run_job() {
  local TF="$1" START="$2"
  echo "=== CREATE JOB $TF ==="
  RESP=$(curl -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"customSymbols\":null,\"timeframe\":\"$TF\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END\"}" 2>&1) || { echo "CREATE_FAILED $TF: $RESP"; return 1; }
  echo "$RESP"
  JOB=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])")
  echo "JOB_ID=$JOB"
  for i in $(seq 1 120); do
    DET=$(curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH")
    STATUS=$(echo "$DET" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'], j.get('processedSymbols',0), '/', j.get('totalSymbols',0), 'candles', j.get('totalCandlesFetched',0), j.get('message',''))")
    echo "poll $i: $STATUS"
    echo "$DET" | python3 -c "import sys,json; s=json.load(sys.stdin)['data']['job']['status']; raise SystemExit(0 if s in ('COMPLETED','FAILED','CANCELLED') else 1)" 2>/dev/null && break
    sleep 15
  done
  echo "=== JOB DETAIL $TF ==="
  curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH" | python3 -c "
import sys,json
j=json.load(sys.stdin)['data']['job']
print('status',j['status'],'fetched',j.get('totalCandlesFetched'),'failures',j.get('failureCount'),'gaps',j.get('totalGaps'))
syms=j.get('symbols',[])
fail=[s for s in syms if s.get('status') not in ('COMPLETED','SKIPPED')]
print('non_ok',len(fail))
for s in fail[:5]:
  print(' ',s.get('symbol'),s.get('status'),s.get('message','')[:80])
"
}

# Preflight first
for TF START in "1m $START_1M" "1d $START_1D" "5m $START_5M"; do
  set -- $TF $START
  echo "=== PREFLIGHT $1 ==="
  curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"customSymbols\":null,\"timeframe\":\"$1\",\"rangeStart\":\"$2\",\"rangeEnd\":\"$END\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print('verdict',d['verdict']); [print(' blocker',b) for b in d.get('blockers',[])]"
done

# Run 1m first (longest), then 1d, then 5m
run_job 1m "$START_1M"
run_job 1d "$START_1D"
run_job 5m "$START_5M"

echo "=== POST-RUN DB CHECK ==="
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
