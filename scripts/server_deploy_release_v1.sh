#!/usr/bin/env bash
# Run ON the Contabo server after SSH login when GitHub Actions deploy fails.
# Usage (on server):
#   cd /opt/stokr/stokr-platform && bash scripts/server_deploy_release_v1.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Pull Release_v1"
git fetch origin Release_v1
git checkout Release_v1
git pull origin Release_v1
DEPLOY_SHA="$(git rev-parse --short HEAD)"
echo "==> Deploy commit: $DEPLOY_SHA ($(git log -1 --format='%s'))"
git log --oneline -5

chmod +x ./deploy.sh ./health-check.sh

echo "==> Restart stack if needed"
./health-check.sh restart || true

echo "==> Deploy API + UI"
./deploy.sh api ui

echo "==> Health"
./health-check.sh check || true
curl -sf http://127.0.0.1:8080/actuator/health && echo
curl -sf -o /dev/null -w "UI HTTP %{http_code}\n" http://127.0.0.1:3000/ || true
UI_BUNDLE="$(curl -sf http://127.0.0.1:3000/ | grep -oE 'assets/index-[^"]+\.js' | head -1 || true)"
echo "==> UI bundle: ${UI_BUNDLE:-unknown}"

echo "==> Verify release (local)"
chmod +x ./scripts/verify_release_deploy.sh
STOKR_API_BASE=http://127.0.0.1:8080 EXPECTED_GIT_COMMIT="$(git rev-parse --short HEAD)" \
  bash ./scripts/verify_release_deploy.sh || echo "WARN: verify_release_deploy.sh reported failures — check output above"

echo "==> Done. Deployed commit: $DEPLOY_SHA"
echo "==> Public URLs:"
echo "  API: http://173.249.55.84:8080/actuator/health"
echo "  UI:  http://173.249.55.84:3000/"
