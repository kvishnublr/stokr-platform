#!/usr/bin/env bash
# Run ONCE on Contabo via web console (Contabo panel → VNC/Serial console).
# SSH password auth is DISABLED on this server — use the Contabo web console.
#
#   cd /opt/stokr/stokr-platform && bash scripts/contabo_bootstrap_autodeploy.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Stokr Contabo auto-deploy bootstrap"
echo "    Project: $ROOT"

# ── 1. GitHub Actions deploy key ─────────────────────────────────────────────
mkdir -p /root/.ssh
chmod 700 /root/.ssh
AUTH=/root/.ssh/authorized_keys
touch "$AUTH"
chmod 600 "$AUTH"
KEY=/root/.ssh/github_actions_deploy

if [ ! -f "$KEY" ]; then
  ssh-keygen -t ed25519 -f "$KEY" -N "" -C "github-actions-stokr-deploy"
  echo "==> Generated new deploy key: $KEY"
fi
PUB="$(cat "${KEY}.pub")"
if ! grep -qF "$PUB" "$AUTH" 2>/dev/null; then
  echo "$PUB" >> "$AUTH"
  echo "==> Added deploy public key to authorized_keys"
else
  echo "==> Deploy public key already in authorized_keys"
fi

# Also install repo-bundled key if present (backward compatible)
PUB_KEY_FILE="$ROOT/deploy/contabo_github_deploy.pub"
if [ -f "$PUB_KEY_FILE" ]; then
  REPO_PUB="$(cat "$PUB_KEY_FILE")"
  if ! grep -qF "$REPO_PUB" "$AUTH" 2>/dev/null; then
    echo "$REPO_PUB" >> "$AUTH"
    echo "==> Added repo-bundled deploy public key"
  fi
fi

# ── 2. Server-side poll deploy (works without GitHub Actions) ────────────────
POLL="$ROOT/scripts/contabo_poll_deploy.sh"
chmod +x "$POLL" "$ROOT/deploy.sh" "$ROOT/health-check.sh" 2>/dev/null || true
CRON_FILE=/etc/cron.d/stokr-platform-deploy
echo "*/2 * * * * root $POLL" > "$CRON_FILE"
chmod 644 "$CRON_FILE"
echo "==> Installed cron: $CRON_FILE (polls Release_v1 every 2 min)"

# ── 3. Pull latest + initial deploy ──────────────────────────────────────────
git fetch origin Release_v1
git checkout Release_v1
git pull origin Release_v1
./health-check.sh restart || true
./deploy.sh api ui

echo ""
echo "================================================================"
echo "BOOTSTRAP COMPLETE"
echo "================================================================"
echo ""
echo "Add these GitHub repo secrets (Settings → Secrets → Actions):"
echo "  DEPLOY_HOST  = 173.249.55.84"
echo "  DEPLOY_USER  = root"
echo "  DEPLOY_PATH  = /opt/stokr/stokr-platform"
echo ""
echo "  DEPLOY_SSH_KEY = paste everything below (including BEGIN/END lines):"
echo "----------------------------------------------------------------"
cat "$KEY"
echo "----------------------------------------------------------------"
echo ""
echo "Verify:"
echo "  curl -sf http://127.0.0.1:8080/actuator/health"
echo "  curl -sf -o /dev/null -w 'UI HTTP %{http_code}\n' http://127.0.0.1:3000/"
echo "  tail -f /var/log/stokr-deploy.log"
