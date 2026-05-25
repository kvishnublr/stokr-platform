#!/usr/bin/env bash
set -euo pipefail
BASE="${1:-http://localhost:8080}"
USER="${2:-admin}"
PASS="${3:-admin}"

login=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
token=$(echo "$login" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null || true)
if [ -z "${token:-}" ]; then
  echo "LOGIN_FAILED: $login"
  exit 1
fi

trader=$(echo "$login" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d.get('userId',''))" 2>/dev/null || true)
if [ -z "${trader:-}" ]; then
  trader="6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
fi

payload=$(cat <<EOF
{
  "traderUserId": "$trader",
  "strategyKey": "OPENING_RANGE_BREAKOUT",
  "symbol": "ITC",
  "side": "BUY",
  "quantity": 1,
  "orderType": "MARKET",
  "executionMode": "PAPER",
  "triggerType": "INSTANT",
  "forceQuantityOne": true,
  "dryRunOnly": false,
  "skipActualBrokerExecution": false,
  "simulateRejection": false,
  "simulateTimeout": false,
  "simulateStaleWebsocket": false,
  "simulateMarginFailure": false,
  "simulateBrokerDisconnect": false,
  "autoSquareOffMinutes": 5
}
EOF
)

echo "Running test signal lab (PAPER) for trader=$trader ..."
run=$(curl -sf -X POST "$BASE/api/admin/test-signal-lab/run" \
  -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' \
  -d "$payload")
echo "$run" | python3 -c "import sys,json; r=json.load(sys.stdin)['data']; print('finalStatus=', r.get('finalStatus')); print('status=', r.get('status')); print('orderId=', r.get('orderId')); failed=[c for c in r.get('checks',[]) if c.get('status')=='FAILED']; print('failedChecks=', len(failed));
[print(' -', c['label'], c.get('message')) for c in failed]"
