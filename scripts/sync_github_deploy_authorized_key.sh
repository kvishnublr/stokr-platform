#!/usr/bin/env bash
# Idempotently install deploy public keys into root authorized_keys.
# Runs on the Contabo host during ./deploy.sh (cron poll or manual deploy).

set -euo pipefail

AUTH="${STOKR_DEPLOY_AUTH_KEYS:-/root/.ssh/authorized_keys}"
mkdir -p "$(dirname "$AUTH")"
chmod 700 "$(dirname "$AUTH")"
touch "$AUTH"
chmod 600 "$AUTH"

install_pub() {
  local pub_file="$1"
  local label="$2"
  if [ ! -f "$pub_file" ]; then
    return 0
  fi
  local pub
  pub="$(tr -d '\r' < "$pub_file")"
  if [ -z "$pub" ]; then
    return 0
  fi
  if grep -qF "$pub" "$AUTH" 2>/dev/null; then
    echo "==> Deploy SSH: $label already authorized"
    return 0
  fi
  echo "$pub" >> "$AUTH"
  echo "==> Deploy SSH: added $label to $AUTH"
}

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
install_pub "${ROOT}/deploy/contabo_github_deploy.pub" "repo deploy key"
install_pub "/root/.ssh/github_actions_deploy.pub" "server deploy key"
