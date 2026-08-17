#!/bin/bash
set -euo pipefail

# ci_ssh_deploy.sh
# Builds and deploys the stokr platform to the production server via SSH.
#
# Required env vars:
#   DEPLOY_HOST  - Server hostname/IP
#   DEPLOY_USER  - SSH user (default: root)
#   DEPLOY_PATH  - Remote deploy path (default: /opt/stokr/stokr-platform)

DEPLOY_HOST="${DEPLOY_HOST:?DEPLOY_HOST not set}"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_PATH="${DEPLOY_PATH:-/opt/stokr/stokr-platform}"
SSH_KEY="$HOME/.ssh/github_actions_deploy"
SSH_OPTS="-i $SSH_KEY -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ConnectTimeout=30"
SSH_CMD="ssh $SSH_OPTS ${DEPLOY_USER}@${DEPLOY_HOST}"
SCP_CMD="scp $SSH_OPTS"

echo "=== Deploying to ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_PATH} ==="
echo ""

# Step 1: Update code on server
echo ">>> Step 1: Pulling latest code..."
$SSH_CMD "cd $DEPLOY_PATH && git fetch origin && git reset --hard origin/Release_v8 && git log -1 --oneline"
echo ""

# Step 2: Build backend
echo ">>> Step 2: Building backend (mvn package)..."
$SSH_CMD "cd $DEPLOY_PATH/stokr-lite/backend && mvn package -q -DskipTests 2>&1 | tail -20"
echo ""

# Step 3: Deploy JAR (sanity-check size first -- a truncated/empty jar must never overwrite
# the running one, since that crash-loops the service with no automatic recovery)
echo ">>> Step 3: Deploying JAR..."
$SSH_CMD "
  set -e
  SRC=$DEPLOY_PATH/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar
  SIZE=\$(stat -c%s \"\$SRC\")
  if [ \"\$SIZE\" -lt 10000000 ]; then
    echo \"REFUSING TO DEPLOY: built jar is only \$SIZE bytes (expected 60MB+) -- build likely failed or produced a truncated artifact\"
    exit 1
  fi
  cp \"\$SRC\" /opt/stokr/stokr-lite.jar && echo \"JAR copied OK (\$SIZE bytes)\"
"
echo ""

# Step 4: Build frontend
echo ">>> Step 4: Building frontend..."
$SSH_CMD "cd $DEPLOY_PATH/stokr-lite/frontend && npm install --frozen-lockfile --prefer-offline 2>&1 | tail -5 && npm run build 2>&1 | tail -10"
echo ""

# Step 5: Deploy frontend (verify the build actually produced an index.html + assets before
# wiping the currently-live directory -- rm -rf on an empty/failed build would take the site
# down with no frontend at all)
echo ">>> Step 5: Deploying frontend..."
$SSH_CMD "
  set -e
  SRC=$DEPLOY_PATH/stokr-lite/frontend/dist
  if [ ! -f \"\$SRC/index.html\" ] || [ -z \"\$(ls -A \"\$SRC/assets\" 2>/dev/null)\" ]; then
    echo \"REFUSING TO DEPLOY: \$SRC is missing index.html or has no assets -- build likely failed\"
    exit 1
  fi
  rm -rf /opt/stokr/ui/* && cp -r \"\$SRC\"/* /opt/stokr/ui/ && chmod -R 755 /opt/stokr/ui/assets/ && echo 'Frontend deployed OK'
"
echo ""

# Step 6: Run Flyway migrations (if any pending)
echo ">>> Step 6: Checking for pending DB migrations..."
$SSH_CMD "cd $DEPLOY_PATH/stokr-lite/backend && ls src/main/resources/db/migration/ | sort | tail -5"
echo ""

# Step 7: Restart backend service
echo ">>> Step 7: Restarting stokr-lite service..."
$SSH_CMD "systemctl restart stokr-lite && sleep 3 && systemctl is-active stokr-lite"
echo ""

# Step 8: Health check
echo ">>> Step 8: Health check..."
sleep 5
$SSH_CMD "curl -s -o /dev/null -w 'HTTP %{http_code}' http://localhost:8081/api/option-arbitrage/health --max-time 10 || curl -s -o /dev/null -w 'HTTP %{http_code}' http://localhost:8081/api/deployments --max-time 10"
echo ""

# Step 9: Verify frontend
echo ">>> Step 9: Verifying frontend..."
$SSH_CMD "curl -s -o /dev/null -w 'Frontend HTTP %{http_code}' https://stokr.in/ --max-time 10 || curl -s -o /dev/null -w 'Frontend HTTP %{http_code}' http://localhost:80/ --max-time 10"
echo ""

echo "=== Deploy complete ==="
