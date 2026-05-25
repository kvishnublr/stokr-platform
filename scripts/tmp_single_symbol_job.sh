#!/bin/bash
set -euo pipefail
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START="2026-03-27T04:30:00Z"

echo creating RELIANCE job...
curl -m 600 -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"CUSTOM","customSymbols":["RELIANCE"],"timeframe":"1m","rangeStart":"'"$START"'","rangeEnd":"'"$END"'"}' | tee /tmp/job_create.json | python3 -m json.tool

JOB=$(python3 -c "import json; print(json.load(open('/tmp/job_create.json'))['data']['jobId'])")
echo job=$JOB
for i in $(seq 1 30); do
  curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'],j.get('totalCandlesFetched'),j.get('message','')[:80])"
  sleep 10
done

docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol,timeframe,COUNT(*),MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date,MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date
FROM marketdata_candles WHERE symbol='RELIANCE' AND deleted=false GROUP BY 1,2;
"
