#!/bin/bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"principal":"admin","password":"password"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
END="2026-05-22T10:00:00Z"
for START in "2026-03-27T03:45:00Z" "2026-03-27T04:30:00Z" "2026-05-21T04:30:00Z" "2026-05-21T05:00:00Z"; do
  echo "--- START=$START ---"
  curl -sf -X POST "$BASE/api/admin/market/backfill/preflight" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"NIFTY_50\",\"timeframe\":\"1m\",\"rangeStart\":\"$START\",\"rangeEnd\":\"$END\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d['verdict'], d.get('blockers',[])[0].get('message','')[:120] if d.get('blockers') else 'OK')"
done

# trader accounts in DB
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT ba.broker_user_id, ba.status, u.principal, ba.updated_at
FROM broker_accounts ba
JOIN auth_users u ON u.id = ba.user_id
WHERE ba.deleted = false AND ba.vendor_code ILIKE 'zerodha'
ORDER BY ba.updated_at DESC LIMIT 5;
"
