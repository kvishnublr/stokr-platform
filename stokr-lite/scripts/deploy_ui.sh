#!/bin/bash
# Deploy script for frontend assets
# Usage: ./deploy_ui.sh
# Run from local machine - SCPs dist/ to server and fixes permissions

set -e

SERVER="root@173.249.55.84"
REMOTE_UI="/opt/stokr/ui"

echo "Building frontend..."
cd "$(dirname "$0")/../frontend"
npm run build

echo "Deploying to server..."
# Clean old assets, copy new, fix permissions
ssh $SERVER "rm -rf $REMOTE_UI/assets"
scp -r dist/assets $SERVER:/tmp/assets_deploy
ssh $SERVER "mv /tmp/assets_deploy $REMOTE_UI/assets"
scp dist/index.html $SERVER:$REMOTE_UI/index.html

# CRITICAL: Fix permissions so nginx (www-data) can read
ssh $SERVER "chmod -R 755 $REMOTE_UI && chmod -R 644 $REMOTE_UI/assets/* 2>/dev/null; echo 'Permissions fixed'"

echo "Deployed! https://stokr.in"
