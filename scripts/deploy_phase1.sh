#!/bin/bash

# Phase 1 Deployment Script
# Deploys Order Flow Analysis Foundation to Contabo

SERVER="173.249.55.84"
USER="root"
JAR_FILE="stokr-bootstrap/target/stokr-bootstrap-*.jar"
DOCKER_IMAGE="stokr-platform-api:phase1"

echo "🚀 Phase 1 Deployment Script"
echo "=============================="
echo "Target: $SERVER"
echo "User: $USER"
echo ""

# Step 1: Build the JAR
echo "Step 1: Building JAR..."
mvn clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi
echo "✅ Build successful"

# Step 2: Get the JAR file
JAR=$(ls $JAR_FILE 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "❌ JAR file not found!"
    exit 1
fi
echo "✅ JAR file: $JAR"

# Step 3: Build Docker image
echo ""
echo "Step 2: Building Docker image..."
docker build -t $DOCKER_IMAGE .
if [ $? -ne 0 ]; then
    echo "❌ Docker build failed!"
    exit 1
fi
echo "✅ Docker image built: $DOCKER_IMAGE"

# Step 4: Deploy to Contabo
echo ""
echo "Step 3: Deploying to Contabo..."
ssh -o StrictHostKeyChecking=no $USER@$SERVER << 'EOF'

# Stop existing container
echo "Stopping existing container..."
docker stop stokr-platform-api || true
docker rm stokr-platform-api || true

# Run new container with Phase 1 configuration
echo "Starting new container..."
docker run -d \
  --name stokr-platform-api \
  --restart always \
  -p 8080:8080 \
  -e STOKR_ORDERFLOW_COLLECTION_ENABLED=false \
  -e STOKR_ORDERFLOW_ENHANCEMENT_ENABLED=false \
  -e DB_HOST=localhost \
  -e DB_PORT=5432 \
  -e DB_NAME=stokr_platform \
  -e DB_USER=postgres \
  -e DB_PASSWORD=root123 \
  stokr-platform-api:phase1

# Wait for startup
sleep 10

# Check if running
if docker ps | grep -q stokr-platform-api; then
    echo "✅ Container is running"
    docker logs --tail 20 stokr-platform-api
else
    echo "❌ Container failed to start"
    docker logs stokr-platform-api
    exit 1
fi

EOF

if [ $? -ne 0 ]; then
    echo "❌ Deployment failed!"
    exit 1
fi

echo ""
echo "✅ Phase 1 Deployment Complete!"
echo ""
echo "Configuration:"
echo "  - Order Flow Collection: DISABLED (set to false)"
echo "  - Order Flow Enhancement: DISABLED (set to false)"
echo ""
echo "Next Steps:"
echo "  1. Monitor logs for 5 minutes: docker logs -f stokr-platform-api"
echo "  2. Verify signal generation continues: Check dashboard"
echo "  3. In 1 week, enable collection: STOKR_ORDERFLOW_COLLECTION_ENABLED=true"
echo "  4. In 2 weeks, enable enhancement: STOKR_ORDERFLOW_ENHANCEMENT_ENABLED=true"
echo ""
echo "URLs:"
echo "  - API: http://173.249.55.84:8080"
echo "  - Dashboard: http://173.249.55.84:8080/adv-enhanced-dashboard"
