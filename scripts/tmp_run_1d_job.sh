#!/bin/bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
START="2025-11-27T03:45:00Z"

curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"CUSTOM","customSymbols":["RELIANCE"],"timeframe":"1d","rangeStart":"'"$START"'","rangeEnd":"'"$END"'"}' \
  | python3 -c "import sys,json; print('preflight',json.load(sys.stdin)['data']['verdict'])"

job=$(curl -m 600 -sf -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"brokerSource":"ZERODHA","symbolGroup":"NIFTY_50","timeframe":"1d","rangeStart":"'"$START"'","rangeEnd":"'"$END"'"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jobId'])")
echo job=$job
for i in $(seq 1 40); do
  curl -sf "$BASE/api/admin/market/backfill/jobs/$job" -H "$AUTH" | python3 -c "import sys,json; j=json.load(sys.stdin)['data']['job']; print(j['status'],j.get('processedSymbols'),j.get('totalCandlesFetched'))"
  sleep 25
done

docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT timeframe, COUNT(DISTINCT symbol), MIN(open_time AT TIME ZONE 'Asia/Kolkata')::date, MAX(open_time AT TIME ZONE 'Asia/Kolkata')::date, COUNT(*)
FROM marketdata_candles WHERE deleted=false AND symbol IN ('RELIANCE','TCS','INFY') GROUP BY 1 ORDER BY 1;
"
