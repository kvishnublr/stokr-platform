#!/usr/bin/env bash
# Run ONCE on Contabo via web console (Contabo panel → VNC/Serial console).
# Password Temp1234 is for the Contabo panel/console — SSH only accepts public keys.
#
#   cd /opt/stokr/stokr-platform && bash scripts/contabo_bootstrap_autodeploy.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Stokr Contabo auto-deploy bootstrap"
echo "    Project: $ROOT"

# ── 1. GitHub Actions deploy key ─────────────────────────────────────────────
PUB_KEY_FILE="$ROOT/deploy/contabo_github_deploy.pub"
if [ ! -f "$PUB_KEY_FILE" ]; then
  echo "ERROR: Missing $PUB_KEY_FILE — git pull Release_v1 first."
  exit 1
fi

mkdir -p /root/.ssh
chmod 700 /root/.ssh
AUTH=/root/.ssh/authorized_keys
touch "$AUTH"
chmod 600 "$AUTH"
PUB="$(cat "$PUB_KEY_FILE")"
if ! grep -qF "$PUB" "$AUTH" 2>/dev/null; then
  echo "$PUB" >> "$AUTH"
  echo "==> Added GitHub Actions deploy public key to authorized_keys"
else
  echo "==> GitHub Actions deploy key already present"
fi

# ── 2. Server-side poll deploy (works even if GitHub Actions secrets broken) ─
POLL="$ROOT/scripts/contabo_poll_deploy.sh"
chmod +x "$POLL" "$ROOT/deploy.sh" "$ROOT/health-check.sh" 2>/dev/null || true
CRON_LINE="*/2 * * * * root $POLL"
CRON_FILE=/etc/cron.d/stokr-platform-deploy
echo "$CRON_LINE" > "$CRON_FILE"
chmod 644 "$CRON_FILE"
echo "==> Installed cron: $CRON_FILE"

# ── 3. Initial deploy ────────────────────────────────────────────────────────
git fetch origin Release_v1
git checkout Release_v1
git pull origin Release_v1
./health-check.sh restart || true
./deploy.sh api ui

echo ""
echo "==> Bootstrap complete."
echo ""
echo "NEXT: Add GitHub repository secrets (Settings → Secrets → Actions):"
echo "  DEPLOY_HOST  = 173.249.55.84"
echo "  DEPLOY_USER  = root"
echo "  DEPLOY_PATH  = /opt/stokr/stokr-platform"
echo "  DEPLOY_SSH_KEY = contents of deploy/contabo_github_deploy.key (see repo maintainer — NOT in git)"
echo ""
echo "Verify:"
echo "  curl -sf http://127.0.0.1:8080/actuator/health"
echo "  curl -sf -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3000/"
echo "  tail -f /var/log/stokr-deploy.log"
