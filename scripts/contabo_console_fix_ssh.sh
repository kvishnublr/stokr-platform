#!/usr/bin/env bash
# Run in Contabo web/serial console as root (SSH password auth is disabled).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd || echo /opt/stokr/stokr-platform)"
PUB_FILE="${ROOT}/deploy/contabo_github_deploy.pub"
if [ -f "$PUB_FILE" ]; then
  PUB="$(tr -d '\r' < "$PUB_FILE")"
else
  PUB='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBYw6au3c/lLOOZTstVl2sv0Z++aIAyM/AsPvbRgANWO stokr@github'
fi
AUTH=/root/.ssh/authorized_keys

mkdir -p /root/.ssh
chmod 700 /root/.ssh
touch "$AUTH"
chmod 600 "$AUTH"
if ! grep -qF "$PUB" "$AUTH" 2>/dev/null; then
  echo "$PUB" >> "$AUTH"
  echo "Added GitHub Actions deploy key to $AUTH"
else
  echo "GitHub Actions deploy key already present in $AUTH"
fi

if [ -d /opt/stokr/stokr-platform ]; then
  cd /opt/stokr/stokr-platform
  git fetch origin Release_v1
  git checkout Release_v1
  git pull origin Release_v1
  chmod +x ./deploy.sh ./health-check.sh ./scripts/sync_github_deploy_authorized_key.sh 2>/dev/null || true
  bash ./scripts/sync_github_deploy_authorized_key.sh || true
  bash scripts/server_deploy_release_v1.sh
else
  echo "WARN: /opt/stokr/stokr-platform not found — key installed; run deploy manually after cloning."
fi
