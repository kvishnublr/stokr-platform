#!/bin/bash
# Stokr Lite -- DB password self-heal watchdog
#
# Something outside this app's own scripts/CI has twice reset the "postgres" role's
# TCP password without updating stokr-lite.env, crash-looping the backend for hours
# until someone noticed and fixed it by hand. Root cause not found despite checking
# cron, systemd timers, and the CI scripts that touch Postgres -- this closes the
# actual damage (extended outage) regardless of what's causing the drift: check every
# few minutes whether the app's configured password still works, and if not, restore
# it from stokr-lite.env via the local peer-auth socket (which isn't affected by TCP
# password drift) and restart the app if it's not currently up.

set -euo pipefail

ENV_FILE="/opt/stokr/stokr-lite.env"
LOG_FILE="/var/log/stokr-db-watchdog.log"
SERVICE="stokr-lite"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"; }

if [ ! -f "$ENV_FILE" ]; then
  log "ERROR: $ENV_FILE not found, skipping check"
  exit 0
fi

EXPECTED_PW=$(grep -oP '^SPRING_DATASOURCE_PASSWORD=\K.*' "$ENV_FILE" || true)
if [ -z "$EXPECTED_PW" ]; then
  log "ERROR: could not read SPRING_DATASOURCE_PASSWORD from $ENV_FILE, skipping check"
  exit 0
fi

check_password() {
  PGPASSWORD="$EXPECTED_PW" psql -h 127.0.0.1 -U postgres -d stokr_lite -c 'SELECT 1;' >/dev/null 2>&1
}

if check_password; then
  exit 0
fi

# One retry after a short pause -- avoids reacting to a transient blip (network hiccup,
# Postgres momentarily busy) as if it were a real credential drift.
sleep 5
if check_password; then
  exit 0
fi

log "DRIFT DETECTED: configured password no longer authenticates (confirmed on retry). Restoring via peer-auth."

if sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD '${EXPECTED_PW}';" >/dev/null 2>&1; then
  log "Password restored successfully."
else
  log "CRITICAL: peer-auth restore FAILED. Manual intervention needed -- local socket auth itself may be broken."
  exit 1
fi

# Confirm the restore actually worked before touching the service.
if ! PGPASSWORD="$EXPECTED_PW" psql -h 127.0.0.1 -U postgres -d stokr_lite -c 'SELECT 1;' >/dev/null 2>&1; then
  log "CRITICAL: password restore did not verify. Manual intervention needed."
  exit 1
fi

if ! systemctl is-active --quiet "$SERVICE"; then
  log "$SERVICE is not active -- restarting."
  systemctl restart "$SERVICE"
  log "$SERVICE restart triggered."
else
  log "$SERVICE was still active (likely just failing new DB connections) -- restarting to pick up the restored password cleanly."
  systemctl restart "$SERVICE"
fi
