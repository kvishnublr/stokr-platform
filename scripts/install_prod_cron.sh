#!/usr/bin/env bash
# Install idempotent production cron entries for stokr-platform ops (Asia/Kolkata).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OPS_SCRIPT="$REPO_ROOT/scripts/prod_ops.sh"
MARKER="# stokr-prod-ops"

for f in "$OPS_SCRIPT" "$SCRIPT_DIR/pre_market_check.sh"; do
  if [[ -f "$f" && ! -x "$f" ]]; then
    chmod +x "$f"
  fi
done
sudo mkdir -p /var/log/stokr 2>/dev/null || mkdir -p "$SCRIPT_DIR/logs"

TMP="$(mktemp)"
crontab -l 2>/dev/null | grep -v "$MARKER" | grep -v "scripts/prod_ops.sh" > "$TMP" || true

{
  cat "$TMP"
  echo ""
  echo "$MARKER"
  echo "TZ=Asia/Kolkata"
  echo "40 5 * * 1-5 $OPS_SCRIPT pre-market >> /var/log/stokr/ops.log 2>&1 $MARKER"
  echo "55 8 * * 1-5 $OPS_SCRIPT pre-open >> /var/log/stokr/ops.log 2>&1 $MARKER"
  echo "*/30 9-16 * * 1-5 $OPS_SCRIPT in-session >> /var/log/stokr/ops.log 2>&1 $MARKER"
  echo "0 6 * * 1-5 $OPS_SCRIPT health >> /var/log/stokr/ops.log 2>&1 $MARKER"
} | crontab -

rm -f "$TMP"
echo "Installed stokr prod cron entries (marker: $MARKER)"
crontab -l | grep "$MARKER" || true
