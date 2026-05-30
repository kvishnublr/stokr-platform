#!/usr/bin/env bash
# Used by GitHub Actions: materialize DEPLOY_SSH_KEY and matching deploy/contabo_github_deploy.pub
set -euo pipefail

install -m 700 -d "$HOME/.ssh"
python3 <<'PY'
import os
from pathlib import Path

required = ("DEPLOY_SSH_KEY",)
missing = [name for name in required if not os.environ.get(name)]
if missing:
    raise SystemExit(f"Missing required env: {', '.join(missing)}")

key = os.environ["DEPLOY_SSH_KEY"].strip()
if "\\n" in key and "\n" not in key:
    key = key.replace("\\n", "\n")

key_path = Path.home() / ".ssh" / "contabo_deploy"
key_path.write_text(key + "\n", encoding="utf-8")
key_path.chmod(0o600)
PY

if ! ssh-keygen -y -f "$HOME/.ssh/contabo_deploy" > "$HOME/.ssh/contabo_deploy.pub"; then
  echo "::error::DEPLOY_SSH_KEY is not a valid SSH private key."
  exit 1
fi

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "$0")/.." && pwd)}"
mkdir -p "$ROOT/deploy"
cp "$HOME/.ssh/contabo_deploy.pub" "$ROOT/deploy/contabo_github_deploy.pub"

echo "Deploy key fingerprint:"
ssh-keygen -l -f "$HOME/.ssh/contabo_deploy.pub"

if [ -n "${DEPLOY_HOST:-}" ]; then
  ssh-keyscan -H -p 22 "$DEPLOY_HOST" >> "$HOME/.ssh/known_hosts" 2>/dev/null || true
fi
