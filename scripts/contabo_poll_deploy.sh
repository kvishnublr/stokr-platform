#!/usr/bin/env bash
# Poll origin/Release_v1 and auto-deploy UI/API when new commits land.
# Install via scripts/contabo_bootstrap_autodeploy.sh (cron every 2 minutes).

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
LOG="${STOKR_DEPLOY_LOG:-/var/log/stokr-deploy.log}"
BRANCH="${STOKR_DEPLOY_BRANCH:-Release_v1}"

log() { echo "[$(date -Iseconds)] $*" | tee -a "$LOG"; }

mkdir -p "$(dirname "$LOG")"
touch "$LOG"

git fetch origin "$BRANCH" >>"$LOG" 2>&1 || { log "git fetch failed"; exit 1; }

LOCAL="$(git rev-parse "$BRANCH" 2>/dev/null || echo none)"
REMOTE="$(git rev-parse "origin/$BRANCH" 2>/dev/null || echo none)"

if [ "$LOCAL" = "$REMOTE" ]; then
  exit 0
fi

log "New commits on origin/$BRANCH ($LOCAL -> $REMOTE)"
git checkout "$BRANCH"
git pull origin "$BRANCH" >>"$LOG" 2>&1
chmod +x ./deploy.sh ./health-check.sh 2>/dev/null || true
./deploy.sh api ui >>"$LOG" 2>&1
log "Deploy finished at $(git rev-parse --short HEAD)"
