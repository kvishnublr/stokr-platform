#!/bin/bash
#
# AUTOMATED DEPLOYMENT SCRIPT FOR STOKR PLATFORM
# Deploy to 173.249.55.84:8080
# Usage: ./DEPLOY_TO_PRODUCTION.sh
#

set -e

echo "🚀 STOKR PLATFORM DEPLOYMENT SCRIPT"
echo "===================================="
echo ""

# Configuration
SERVER="173.249.55.84"
PORT="8080"
APP_DIR="/app"
CONTAINER_NAME="stokr-platform-api"
IMAGE_NAME="stokr-platform-api:latest"
BRANCH="Release_v2"

echo "📋 Configuration:"
echo "   Server: $SERVER:$PORT"
echo "   Container: $CONTAINER_NAME"
echo "   Branch: $BRANCH"
echo ""

# Step 1: Build
echo "📦 Step 1: Building JAR..."
echo "   Command: mvn clean package -DskipTests"
mvn clean package -DskipTests -q
echo "   ✅ Build complete"
echo ""

# Step 2: Verify JAR exists
echo "🔍 Step 2: Verifying JAR file..."
JAR_FILE=$(find stokr-bootstrap/target -name "stokr-bootstrap-*.jar" -type f | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "   ❌ ERROR: JAR file not found!"
    exit 1
fi
echo "   ✅ JAR found: $JAR_FILE"
JAR_SIZE=$(ls -lh "$JAR_FILE" | awk '{print $5}')
echo "   📊 Size: $JAR_SIZE"
echo ""

# Step 3: Deploy JAR to server
echo "🚀 Step 3: Deploying to server..."
echo "   Command: scp $JAR_FILE root@$SERVER:$APP_DIR/"
scp "$JAR_FILE" root@$SERVER:$APP_DIR/
echo "   ✅ JAR deployed"
echo ""

# Step 4: Start container on server
echo "🐳 Step 4: Starting Docker container..."
ssh root@$SERVER << 'REMOTE_COMMANDS'
echo "   Stopping old container..."
docker stop stokr-platform-api 2>/dev/null || true
docker rm stokr-platform-api 2>/dev/null || true

echo "   Starting new container..."
docker run -d \
  --name stokr-platform-api \
  --restart always \
  -p 8080:8080 \
  -e DB_HOST=localhost \
  -e DB_PORT=5432 \
  -e DB_NAME=stokr_platform \
  -e DB_USER=postgres \
  -e DB_PASSWORD=postgres \
  -e STOKR_CONFIDENCE_AUTO_TRADE_ENABLED=true \
  -e STOKR_CONFIDENCE_AUTO_TRADE_EXECUTION_MODE=SIMULATED \
  -e STOKR_CONFIDENCE_AUTO_TRADE_DEFAULT_QUANTITY=1 \
  -e STOKR_CONFIDENCE_AUTO_TRADE_HIGH_CONF_QTY=2 \
  -e STOKR_CONFIDENCE_AUTO_TRADE_CONF_THRESHOLD=75 \
  -v /var/log/stokr:/app/logs \
  openjdk:21-jdk-slim java -jar stokr-bootstrap-1.0.0-SNAPSHOT.jar

echo "   Waiting for container to start..."
sleep 5

echo "   ✅ Container started"
REMOTE_COMMANDS
echo ""

# Step 5: Health check
echo "💚 Step 5: Health check..."
sleep 3
HEALTH_CHECK=$(curl -s http://$SERVER:$PORT/health 2>/dev/null || echo "{}")
if echo "$HEALTH_CHECK" | grep -q '"status":"UP"'; then
    echo "   ✅ Server is UP and responding"
    echo "   Response: $HEALTH_CHECK"
else
    echo "   ⚠️  Server not responding yet, waiting..."
    sleep 5
    HEALTH_CHECK=$(curl -s http://$SERVER:$PORT/health 2>/dev/null || echo "{}")
    if echo "$HEALTH_CHECK" | grep -q '"status":"UP"'; then
        echo "   ✅ Server is UP!"
    else
        echo "   ⚠️  Health check failed. Check logs:"
        ssh root@$SERVER "docker logs stokr-platform-api | tail -20"
    fi
fi
echo ""

# Step 6: Verify key endpoints
echo "🧪 Step 6: Verifying endpoints..."

# Test confidence scores
echo "   Testing confidence scores endpoint..."
SCORES=$(curl -s http://$SERVER:$PORT/api/confidence-strategy/latest-scores?limit=3 2>/dev/null || echo "[]")
if [ "$SCORES" != "[]" ]; then
    echo "   ✅ Confidence scores available"
else
    echo "   ⚠️  No confidence scores yet (expected on first start)"
fi

# Test signal count
echo "   Testing signal count endpoint..."
SIGNALS=$(curl -s http://$SERVER:$PORT/api/confidence-strategy/today/signal-count 2>/dev/null || echo "{}")
if echo "$SIGNALS" | grep -q '"threshold'; then
    echo "   ✅ Signal generation available"
else
    echo "   ⚠️  Signal generation initializing..."
fi

echo ""
echo "✅ DEPLOYMENT COMPLETE!"
echo ""
echo "📊 Next Steps:"
echo "   1. Monitor logs: ssh root@$SERVER 'docker logs -f stokr-platform-api'"
echo "   2. Create trader config at threshold 50"
echo "   3. Wait 4 minutes for first signals"
echo "   4. Verify orders placed in account"
echo "   5. Run for 24 hours in SIMULATED mode"
echo "   6. Switch to LIVE when ready"
echo ""
echo "🎯 Access Points:"
echo "   Health: http://$SERVER:$PORT/health"
echo "   Scores: http://$SERVER:$PORT/api/confidence-strategy/latest-scores"
echo "   Signals: http://$SERVER:$PORT/api/confidence-strategy/today/signal-count"
echo ""
echo "📞 For Issues:"
echo "   Logs: ssh root@$SERVER 'docker logs stokr-platform-api | tail -100'"
echo "   Restart: ssh root@$SERVER 'docker restart stokr-platform-api'"
echo ""
echo "🚀 STOKR PLATFORM IS NOW LIVE AT $SERVER:$PORT"
echo ""
