#!/bin/bash
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8080}"
PRINCIPAL="${PRINCIPAL:-admin}"
PASSWORD="${PASSWORD:-password}"
START="${START:-2026-05-18T03:45:00Z}"
END="${END:-2026-05-25T10:00:00Z}"
SYMS="${SYMS:-[\"ADANIENT\",\"HINDALCO\",\"JIOFIN\",\"TATAMOTORS\",\"TRENT\"]}"

for i in $(seq 1 30); do
  curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break
  sleep 3
done

TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"principal\":\"$PRINCIPAL\",\"password\":\"$PASSWORD\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
AUTH="Authorization: Bearer $TOKEN"

echo "=== preflight ==="
curl -s -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"CUSTOM\",\"customSymbols\":$SYMS,\"timeframe\":\"1m\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END\"}" \
  -o /tmp/bf_preflight.json
python3 -c 'import json; r=json.load(open("/tmp/bf_preflight.json")); print(json.dumps(r, indent=2)[:2500])'

echo "=== start job (skip strict preflight if API allows) ==="
curl -s -X POST "$BASE/api/admin/market/backfill/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"CUSTOM\",\"customSymbols\":$SYMS,\"timeframe\":\"1m\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END\"}" \
  -o /tmp/bf_create.json
cat /tmp/bf_create.json
JOB=$(python3 -c 'import json; r=json.load(open("/tmp/bf_create.json")); print(r.get("data",{}).get("jobId") or "")' 2>/dev/null || true)
if [ -z "$JOB" ]; then
  echo "Job create failed; exiting"
  exit 1
fi
echo "jobId=$JOB"

for i in $(seq 1 80); do
  curl -sf "$BASE/api/admin/market/backfill/jobs/$JOB" -H "$AUTH" -o /tmp/bf_job.json
  python3 -c 'import json; j=json.load(open("/tmp/bf_job.json"))["data"]["job"]; print(j["status"], j.get("processedSymbols"), "/", j.get("totalSymbols"), "candles", j.get("totalCandlesFetched"), "fail", j.get("failureCount"))'
  st=$(python3 -c 'import json; print(json.load(open("/tmp/bf_job.json"))["data"]["job"]["status"])')
  case "$st" in COMPLETED|FAILED|CANCELLED|PARTIAL) break ;; esac
  sleep 15
done

echo "=== DB verify ==="
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, count(distinct date_trunc('day', open_time)) as trading_days, min(open_time::date), max(open_time::date), count(*) bars
FROM marketdata_candles
WHERE timeframe='1m' AND deleted=false
  AND symbol IN ('ADANIENT','HINDALCO','JIOFIN','TATAMOTORS','TRENT')
GROUP BY symbol ORDER BY symbol;
"
