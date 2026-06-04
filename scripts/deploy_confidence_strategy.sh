#!/bin/bash

# Confidence-Based Strategy System Deployment Script
# Deploys to Contabo production server (173.249.55.84)

set -e

SERVER="173.249.55.84"
USER="root"
DOCKER_IMAGE="stokr-platform-api:confidence-strategy"
JAR_FILE="stokr-bootstrap/target/stokr-bootstrap-*.jar"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║  🚀 CONFIDENCE STRATEGY SYSTEM - PRODUCTION DEPLOYMENT     ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Target Server: $SERVER"
echo "Docker Image: $DOCKER_IMAGE"
echo ""

# Step 1: Check if JAR exists
echo "Step 1: Checking JAR file..."
JAR=$(ls $JAR_FILE 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "❌ JAR file not found! Build first: mvn clean package -DskipTests"
    exit 1
fi
echo "✅ JAR found: $JAR"
echo ""

# Step 2: Build Docker image
echo "Step 2: Building Docker image..."
docker build -t $DOCKER_IMAGE . || {
    echo "❌ Docker build failed!"
    exit 1
}
echo "✅ Docker image built: $DOCKER_IMAGE"
echo ""

# Step 3: Deploy to Contabo
echo "Step 3: Deploying to Contabo..."
ssh -o StrictHostKeyChecking=no $USER@$SERVER << 'DEPLOY_SCRIPT'

echo "╔════════════════════════════════════════════════════════════╗"
echo "║  🔄 DEPLOYING CONFIDENCE STRATEGY SYSTEM                   ║"
echo "╚════════════════════════════════════════════════════════════╝"

# Stop existing container
echo "Stopping existing container..."
docker stop stokr-platform-api || true
docker rm stokr-platform-api || true
echo "✅ Old container removed"
echo ""

# Load new Docker image
echo "Loading new Docker image..."
docker load -i /tmp/stokr-platform-api-confidence-strategy.tar || true

# Run new container
echo "Starting new container with Confidence Strategy System..."
docker run -d \
  --name stokr-platform-api \
  --restart always \
  -p 8080:8080 \
  -e STOKR_CONFIDENCE_CALCULATOR_ENABLED=true \
  -e STOKR_CONFIDENCE_GENERATOR_ENABLED=true \
  -e STOKR_CONFIDENCE_CALCULATOR_INTERVAL_MS=60000 \
  -e STOKR_CONFIDENCE_GENERATOR_INTERVAL_MS=120000 \
  -e DB_HOST=localhost \
  -e DB_PORT=5432 \
  -e DB_NAME=stokr_platform \
  -e DB_USER=postgres \
  -e DB_PASSWORD=root123 \
  stokr-platform-api:confidence-strategy

sleep 10

# Verify container is running
if docker ps | grep -q stokr-platform-api; then
    echo "✅ Container is running!"
    echo ""
    echo "📊 Recent logs:"
    docker logs --tail 10 stokr-platform-api
else
    echo "❌ Container failed to start"
    docker logs stokr-platform-api
    exit 1
fi

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  ✅ DEPLOYMENT COMPLETE                                   ║"
echo "╚════════════════════════════════════════════════════════════╝"

DEPLOY_SCRIPT

echo ""
echo "✅ Deployment script executed successfully!"
echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  📊 VERIFICATION ENDPOINTS                                ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Test Confidence Calculation:"
echo "  POST http://173.249.55.84:8080/api/confidence-strategy/test/calculate-now"
echo ""
echo "Test Signal Generation:"
echo "  POST http://173.249.55.84:8080/api/confidence-strategy/test/generate-signals-now"
echo ""
echo "Get Today's Signal Counts:"
echo "  GET http://173.249.55.84:8080/api/confidence-strategy/today/signal-count"
echo ""
echo "Set Trader Threshold:"
echo "  POST http://173.249.55.84:8080/api/confidence-strategy/config"
echo "  Body: {\"traderId\": \"uuid\", \"threshold\": 70}"
echo ""
echo "Get Dashboard Stats:"
echo "  GET http://173.249.55.84:8080/api/confidence-strategy/dashboard/stats"
echo ""

echo "🚀 System is now live!"
