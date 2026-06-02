#!/bin/bash
# ============================================================
# Deploy PRE_OPEN_GAP_OI strategy to your server
# Usage: ./scripts/deploy_pre_open_gap_oi.sh <ssh-user>@<server-ip>
# Example: ./scripts/deploy_pre_open_gap_oi.sh root@173.249.55.84
#
# BEFORE RUNNING:
#   1. Set up SSH key auth: ssh-copy-id root@your-server
#   2. Remove password auth from server: PasswordAuthentication no
#   3. Run DB script first (see register_pre_open_gap_oi_strategy.sql)
# ============================================================

set -e

SERVER="${1:?Usage: $0 user@server}"
REMOTE_DIR="/opt/stokr"   # adjust to your deployment path

echo "==> Building stokr-strategy module..."
./mvnw clean package -pl stokr-strategy -am -DskipTests -q

echo "==> Copying JAR to server..."
JAR=$(ls stokr-strategy/target/stokr-strategy-*.jar | head -1)
scp "$JAR" "$SERVER:$REMOTE_DIR/stokr-strategy.jar"

echo "==> Copying DB registration script..."
scp scripts/register_pre_open_gap_oi_strategy.sql "$SERVER:$REMOTE_DIR/"

echo "==> Running DB registration on server..."
ssh "$SERVER" "psql \$DATABASE_URL -f $REMOTE_DIR/register_pre_open_gap_oi_strategy.sql"

echo "==> Restarting stokr-strategy service..."
ssh "$SERVER" "systemctl restart stokr-strategy || docker compose -f $REMOTE_DIR/docker-compose.yml restart stokr-strategy"

echo "==> Tailing logs (Ctrl+C to stop)..."
ssh "$SERVER" "journalctl -u stokr-strategy -f --since now || docker compose -f $REMOTE_DIR/docker-compose.yml logs -f stokr-strategy"
