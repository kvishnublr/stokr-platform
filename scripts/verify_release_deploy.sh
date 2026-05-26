#!/usr/bin/env bash
# Verify Release_v1 deploy: signal replay, backtest coverage, backfill preflight.
# Usage:
#   STOKR_API_BASE=http://173.249.55.84:8080 bash scripts/verify_release_deploy.sh
#   EXPECTED_GIT_COMMIT=0a19c8f bash scripts/verify_release_deploy.sh

set -euo pipefail

BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
ADMIN_USER="${STOKR_ADMIN_USER:-admin}"
ADMIN_PASS="${STOKR_ADMIN_PASS:-password}"
EXPECTED_COMMIT="${EXPECTED_GIT_COMMIT:-0a19c8f}"
STRATEGY="${STOKR_VERIFY_STRATEGY:-NSE_SPIKE_DETECTION}"
SYMBOL="${STOKR_VERIFY_SYMBOL:-RELIANCE}"

pass=0
fail=0

ok() { echo "PASS: $*"; pass=$((pass + 1)); }
bad() { echo "FAIL: $*" >&2; fail=$((fail + 1)); }

login() {
  curl -sf -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"principal\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])"
}

echo "==> Verify deploy against $BASE"
TOKEN="$(login)" || { bad "login failed"; exit 1; }
ok "admin login"

TODAY="$(TZ=Asia/Kolkata date +%F)"

echo "==> Ops / deploy metadata"
OPS="$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE/api/admin/ops/status" || echo '{}')"
GIT_COMMIT="$(echo "$OPS" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); print(d.get('gitCommit','unknown'))" 2>/dev/null || echo unknown)"
echo "    gitCommit=$GIT_COMMIT (expected prefix: $EXPECTED_COMMIT)"
if [[ "$GIT_COMMIT" == "$EXPECTED_COMMIT"* ]] || [[ "$GIT_COMMIT" == "${EXPECTED_COMMIT:0:7}"* ]]; then
  ok "git commit matches expected release"
elif [[ "$GIT_COMMIT" == "unknown" ]]; then
  bad "git commit unknown — redeploy API with STOKR_GIT_COMMIT set"
else
  bad "git commit $GIT_COMMIT does not match expected $EXPECTED_COMMIT — server is stale"
fi

echo "==> Signal replay preflight endpoint"
PREFLIGHT_CODE="$(curl -s -o /tmp/stokr_preflight.json -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/admin/signals/replay/preflight?strategyKey=$STRATEGY&from=$TODAY&to=$TODAY")"
if [[ "$PREFLIGHT_CODE" == "200" ]]; then
  ok "replay preflight endpoint deployed"
  READY="$(python3 -c "import json; print(json.load(open('/tmp/stokr_preflight.json'))['data'].get('ready', False))")"
  echo "    ready=$READY"
  if [[ "$READY" == "True" ]]; then
    ok "replay preflight ready for $TODAY"
  else
    bad "replay preflight not ready — check blockers in /tmp/stokr_preflight.json"
    python3 -m json.tool /tmp/stokr_preflight.json || true
  fi
else
  bad "replay preflight HTTP $PREFLIGHT_CODE — signal replay fix NOT deployed"
fi

echo "==> Backtest coverage bounds"
BOUNDS="$(curl -sf -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/backtest/coverage/bounds?symbol=$SYMBOL&timeframe=1m" || echo '{}')"
if echo "$BOUNDS" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); exit(0 if d.get('coveredFrom') else 1)" 2>/dev/null; then
  ok "backtest coverage bounds"
else
  bad "backtest coverage bounds missing or invalid"
fi

echo "==> Backtest readiness (clamped window)"
COVERED_TO="$(echo "$BOUNDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('effectiveReplayEnd',''))")"
READINESS="$(curl -sf -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/backtest/coverage/readiness?symbol=$SYMBOL&timeframe=1m&from=${COVERED_TO}&to=${COVERED_TO}" || echo '{}')"
STATE="$(echo "$READINESS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('state',''))" 2>/dev/null || echo '')"
if [[ "$STATE" == "READY" ]] || [[ "$STATE" == "STALE" ]]; then
  ok "backtest readiness state=$STATE"
else
  bad "backtest readiness state=$STATE"
fi

echo "==> Signal replay execution (delta check)"
REPLAY_BEFORE="$(curl -sf -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/admin/signals?page=0&size=1&includeReplayAndLab=true&strategyKey=$STRATEGY" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('totalElements',0))" 2>/dev/null || echo 0)"
REPLAY="$(curl -sf -X POST -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/admin/signals/replay?strategyKey=$STRATEGY&from=$TODAY&to=$TODAY" || echo '{}')"
REPLAY_STATUS="$(echo "$REPLAY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('status',''))" 2>/dev/null || echo '')"
if [[ "$REPLAY_STATUS" != "STARTED" ]]; then
  bad "replay did not start: $REPLAY"
else
  ok "replay job started"
  EST_BARS="$(echo "$REPLAY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('estimatedBars',0))" 2>/dev/null || echo 0)"
  WAIT_SEC=$((75 + EST_BARS / 500))
  if [[ "$WAIT_SEC" -gt 300 ]]; then WAIT_SEC=300; fi
  echo "    waiting ${WAIT_SEC}s for async replay (~${EST_BARS} bars)..."
  sleep "$WAIT_SEC"
  REPLAY_AFTER="$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/admin/signals?page=0&size=1&includeReplayAndLab=true&strategyKey=$STRATEGY" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('totalElements',0))" 2>/dev/null || echo 0)"
  DELTA=$((REPLAY_AFTER - REPLAY_BEFORE))
  echo "    replay signals before=$REPLAY_BEFORE after=$REPLAY_AFTER delta=$DELTA"
  if [[ "$DELTA" -gt 0 ]]; then
    ok "replay generated $DELTA new signal(s)"
  else
    bad "replay produced 0 new REPLAY signals (check Signal Monitor Replay/Lab tab)"
  fi
fi

echo "==> Market backfill preflight"
BF_START="$(echo "$BOUNDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('coveredFrom',''))")"
BF_END="$(echo "$BOUNDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('effectiveReplayEnd',''))")"
BF_CODE="$(curl -s -o /tmp/stokr_backfill.json -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "$BASE/api/admin/market/backfill/preflight" \
  -d "{\"brokerSource\":\"ZERODHA\",\"symbolGroup\":\"CUSTOM\",\"customSymbols\":[\"$SYMBOL\"],\"timeframe\":\"1m\",\"rangeStart\":\"$BF_START\",\"rangeEnd\":\"$BF_END\"}")"
if [[ "$BF_CODE" == "200" ]]; then
  ok "market backfill preflight HTTP 200"
else
  bad "market backfill preflight HTTP $BF_CODE"
fi

echo ""
echo "Summary: $pass passed, $fail failed"
if [[ "$fail" -gt 0 ]]; then
  echo ""
  echo "If deploy is stale, run on Contabo:"
  echo "  cd /opt/stokr/stokr-platform && bash scripts/server_deploy_release_v1.sh"
  exit 1
fi
