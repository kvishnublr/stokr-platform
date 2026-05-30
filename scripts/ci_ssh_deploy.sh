#!/usr/bin/env bash
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:?}"
DEPLOY_USER="${DEPLOY_USER:?}"
DEPLOY_PATH="${DEPLOY_PATH:?}"
KEY="${HOME}/.ssh/contabo_deploy"
TRIES="${SSH_DEPLOY_RETRIES:-3}"
WAIT_SEC="${SSH_DEPLOY_RETRY_WAIT_SEC:-20}"

remote_path="$(printf '%q' "$DEPLOY_PATH")"
remote_cmd="cd ${remote_path} && bash scripts/sync_github_deploy_authorized_key.sh && bash scripts/server_deploy_release_v1.sh"

attempt=1
while [ "$attempt" -le "$TRIES" ]; do
  echo "SSH deploy attempt ${attempt}/${TRIES}..."
  if ssh \
    -i "$KEY" \
    -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes \
    -o ConnectTimeout=60 \
    -p 22 \
    "${DEPLOY_USER}@${DEPLOY_HOST}" \
    "$remote_cmd"; then
    echo "Deploy succeeded on attempt ${attempt}"
    exit 0
  fi
  if [ "$attempt" -lt "$TRIES" ]; then
    echo "SSH failed — retrying in ${WAIT_SEC}s (install key via Contabo console if this persists)"
    sleep "$WAIT_SEC"
  fi
  attempt=$((attempt + 1))
done

PUB="$(cat "${KEY}.pub" 2>/dev/null || true)"
echo "::error::SSH deploy failed after ${TRIES} attempts (Permission denied / publickey)."
echo "::error::Postgres emergency install cannot modify host authorized_keys when Postgres runs in Docker."
echo ""
echo "=== ONE-TIME FIX (Contabo web/serial console as root) ==="
echo "curl -fsSL https://raw.githubusercontent.com/kvishnublr/stokr-platform/Release_v1/scripts/contabo_console_fix_ssh.sh | bash"
echo ""
echo "Or paste this public key into /root/.ssh/authorized_keys:"
echo "${PUB}"
exit 255
