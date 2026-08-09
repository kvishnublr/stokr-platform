#!/bin/bash
set -euo pipefail

# ci_prepare_deploy_ssh.sh
# Writes DEPLOY_SSH_KEY secret to ~/.ssh/github_actions_deploy
# Extracts the public key to deploy/contabo_github_deploy.pub

SSH_DIR="$HOME/.ssh"
KEY_PATH="$SSH_DIR/github_actions_deploy"
PUB_PATH="deploy/contabo_github_deploy.pub"

if [ -z "${DEPLOY_SSH_KEY:-}" ]; then
  echo "ERROR: DEPLOY_SSH_KEY environment variable is not set"
  exit 1
fi

mkdir -p "$SSH_DIR"
chmod 700 "$SSH_DIR"

# Handle escaped newlines from GitHub secrets
key="${DEPLOY_SSH_KEY}"
if [[ "$key" == *"\\"*n* ]] && [[ "$key" != *$'\n'* ]]; then
  key="${key//\\n/$'\n'}"
fi

echo "$key" > "$KEY_PATH"
chmod 600 "$KEY_PATH"

# Extract public key
mkdir -p "$(dirname "$PUB_PATH")"
ssh-keygen -y -f "$KEY_PATH" > "$PUB_PATH"

# Add host to known_hosts
DEPLOY_HOST="${DEPLOY_HOST:-173.249.55.84}"
ssh-keyscan -H -p 22 "$DEPLOY_HOST" >> "$SSH_DIR/known_hosts" 2>/dev/null || true

echo "Deploy SSH key prepared:"
echo "  Private: $KEY_PATH"
echo "  Public:  $PUB_PATH"
echo "  Fingerprint: $(ssh-keygen -lf "$PUB_PATH" 2>/dev/null | awk '{print $2}')"
