#!/usr/bin/env bash
# Pre-market readiness wrapper — calls prod_ops pre-market + health, prints PASS/FAIL.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OPS="$SCRIPT_DIR/prod_ops.sh"

PASS=0
FAIL=0

check() {
  local desc="$1"
  local ok="$2"
  if [[ "$ok" == "1" ]]; then
    echo "PASS $desc"
    PASS=$((PASS + 1))
  else
    echo "FAIL $desc"
    FAIL=$((FAIL + 1))
  fi
}

parse_json_flag() {
  local json="$1"
  local key="$2"
  printf '%s' "$json" | python3 -c "
import sys, json
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print(0)
    sys.exit(0)
data = d.get('data', d)
val = data.get('$key')
print(1 if val else 0)
"
}

echo "=== Pre-market automation ==="
PRE_OUT="$("$OPS" pre-market 2>&1)" || true
echo "$PRE_OUT"

OAUTH_BLOCKER="$(parse_json_flag "$PRE_OUT" oauthRequired)"
HEALTHY="$(parse_json_flag "$PRE_OUT" healthy)"

if [[ "$OAUTH_BLOCKER" == "1" ]]; then
  check "Zerodha OAuth" 0
  echo "NOTE: Human OAuth required when refresh token is missing — use admin broker-infrastructure connect."
else
  check "Zerodha OAuth" 1
fi
check "pre-market healthy" "$HEALTHY"

echo ""
echo "=== Health report ==="
HEALTH_OUT="$("$OPS" health 2>&1)" || true
echo "$HEALTH_OUT"
HR_HEALTHY="$(parse_json_flag "$HEALTH_OUT" healthy)"
check "health-report healthy" "$HR_HEALTHY"

echo ""
echo "=================================================="
echo "PASS: $PASS  FAIL: $FAIL  TOTAL: $((PASS + FAIL))"
if [[ "$FAIL" -eq 0 ]]; then
  echo "PRE-MARKET CHECK: PASS"
  exit 0
else
  echo "PRE-MARKET CHECK: FAIL"
  exit 1
fi
