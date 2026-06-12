#!/bin/bash

################################################################################
# RELEASE_V2 PRODUCTION DEPLOYMENT SCRIPT
# Target: new.stokr.in (173.249.55.84)
# Date: 2026-06-06
# Status: READY FOR EXECUTION
################################################################################

set -e  # Exit on any error

# Configuration
PRODUCTION_SERVER="173.249.55.84"
PRODUCTION_USER="root"  # Change if needed
SSH_PORT="22"
DOCKER_REGISTRY="docker.io"  # Change to your registry
IMAGE_TAG="v2-$(date +%Y%m%d-%H%M%S)"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}RELEASE_V2 PRODUCTION DEPLOYMENT${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════${NC}"

################################################################################
# PHASE 1: PRE-DEPLOYMENT CHECKS
################################################################################

echo -e "\n${BLUE}PHASE 1: Pre-Deployment Checks${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check if JAR exists
if [ ! -f "stokr-bootstrap/target/stokr-bootstrap-1.0.0-SNAPSHOT.jar" ]; then
    echo -e "${RED}ERROR: JAR file not found!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ JAR file found${NC}"

# Check if UI dist exists
if [ ! -d "stokr-ui/dist" ]; then
    echo -e "${RED}ERROR: UI dist directory not found!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ UI bundle found${NC}"

# Check SSH connectivity
echo "Checking SSH connectivity to ${PRODUCTION_SERVER}..."
if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} "echo 'SSH OK'" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ SSH connectivity confirmed${NC}"
else
    echo -e "${RED}ERROR: Cannot connect to production server${NC}"
    echo "Command: ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER}"
    exit 1
fi

# Check Docker on production
echo "Checking Docker installation..."
if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} "docker --version" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Docker installed${NC}"
else
    echo -e "${RED}ERROR: Docker not installed on production server${NC}"
    exit 1
fi

# Check PostgreSQL connectivity
echo "Checking PostgreSQL connectivity..."
if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} "pg_isready -h localhost -p 5432" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ PostgreSQL accessible${NC}"
else
    echo -e "${RED}WARNING: PostgreSQL may not be accessible (will check after deployment)${NC}"
fi

################################################################################
# PHASE 2: DATABASE BACKUP
################################################################################

echo -e "\n${BLUE}PHASE 2: Database Backup${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

BACKUP_FILE="stokr_platform_$(date +%Y%m%d_%H%M%S).sql.gz"
BACKUP_PATH="/backups/${BACKUP_FILE}"

echo "Creating database backup: ${BACKUP_FILE}..."
ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} << 'EOF'
    # Create backups directory if it doesn't exist
    mkdir -p /backups

    # Backup database
    PGPASSWORD=stokr pg_dump -U stokr -h localhost stokr_platform | gzip > /backups/stokr_platform_$(date +%Y%m%d_%H%M%S).sql.gz

    # Keep only last 7 backups
    ls -1t /backups/stokr_platform_*.sql.gz | tail -n +8 | xargs rm -f
EOF

echo -e "${GREEN}✅ Database backup created${NC}"

################################################################################
# PHASE 3: BUILD DOCKER IMAGES
################################################################################

echo -e "\n${BLUE}PHASE 3: Build Docker Images${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Build backend image
echo "Building stokr-api Docker image..."
docker build -t stokr-api:${IMAGE_TAG} \
    -f stokr-bootstrap/Dockerfile \
    --build-arg JAR_FILE=target/stokr-bootstrap-1.0.0-SNAPSHOT.jar \
    stokr-bootstrap/

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Backend image built: stokr-api:${IMAGE_TAG}${NC}"
else
    echo -e "${RED}ERROR: Failed to build backend image${NC}"
    exit 1
fi

# Build frontend image
echo "Building stokr-ui Docker image..."
docker build -t stokr-ui:${IMAGE_TAG} \
    stokr-ui/

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Frontend image built: stokr-ui:${IMAGE_TAG}${NC}"
else
    echo -e "${RED}ERROR: Failed to build frontend image${NC}"
    exit 1
fi

################################################################################
# PHASE 4: PUSH IMAGES TO REGISTRY (Optional)
################################################################################

echo -e "\n${BLUE}PHASE 4: Push Images to Registry${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

read -p "Push images to registry? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Pushing images..."
    docker tag stokr-api:${IMAGE_TAG} ${DOCKER_REGISTRY}/stokr-api:${IMAGE_TAG}
    docker push ${DOCKER_REGISTRY}/stokr-api:${IMAGE_TAG}

    docker tag stokr-ui:${IMAGE_TAG} ${DOCKER_REGISTRY}/stokr-ui:${IMAGE_TAG}
    docker push ${DOCKER_REGISTRY}/stokr-ui:${IMAGE_TAG}

    echo -e "${GREEN}✅ Images pushed to registry${NC}"
else
    echo "Skipping registry push. Using local Docker images."
fi

################################################################################
# PHASE 5: DEPLOY TO PRODUCTION
################################################################################

echo -e "\n${BLUE}PHASE 5: Deploy to Production${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo "Connecting to production server..."

ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} << EOFDEPLOYMENT

# Navigate to deployment directory
cd /opt/stokr-platform || mkdir -p /opt/stokr-platform
cd /opt/stokr-platform

# Create docker-compose.yml if doesn't exist
if [ ! -f "docker-compose.yml" ]; then
    cat > docker-compose.yml << 'EOFCOMPOSE'
version: '3.8'

services:
  stokr-api:
    image: stokr-api:${IMAGE_TAG}
    container_name: stokr-api-v2
    ports:
      - "8080:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/stokr_platform
      - SPRING_DATASOURCE_USERNAME=stokr
      - SPRING_DATASOURCE_PASSWORD=stokr
      - SERVER_PORT=8080
      - MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
    depends_on:
      - postgres
    networks:
      - stokr-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  stokr-ui:
    image: stokr-ui:${IMAGE_TAG}
    container_name: stokr-ui-v2
    ports:
      - "80:3000"
    environment:
      - REACT_APP_API_URL=http://localhost:8080
    depends_on:
      - stokr-api
    networks:
      - stokr-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 20s

  postgres:
    image: postgres:15
    container_name: stokr-postgres
    environment:
      - POSTGRES_DB=stokr_platform
      - POSTGRES_USER=stokr
      - POSTGRES_PASSWORD=stokr
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - stokr-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U stokr"]
      interval: 10s
      timeout: 5s
      retries: 5

networks:
  stokr-network:
    driver: bridge

volumes:
  postgres_data:
    driver: local
EOFCOMPOSE

    echo "✅ docker-compose.yml created"
fi

# Stop existing containers
echo "Stopping existing services..."
docker-compose down || true

# Wait for cleanup
sleep 5

# Pull images (if using registry)
echo "Pulling Docker images..."
docker-compose pull || echo "Using local images"

# Start services
echo "Starting Release_v2 services..."
docker-compose up -d

# Wait for services to start
echo "Waiting for services to start..."
sleep 10

# Check container status
docker-compose ps

echo "✅ Deployment complete"

EOFDEPLOYMENT

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Deployment successful${NC}"
else
    echo -e "${RED}ERROR: Deployment failed${NC}"
    exit 1
fi

################################################################################
# PHASE 6: VERIFY DEPLOYMENT
################################################################################

echo -e "\n${BLUE}PHASE 6: Verify Deployment${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo "Verifying API health..."
for i in {1..10}; do
    if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} \
        "curl -s http://localhost:8080/api/health | grep -q 'UP'" 2>/dev/null; then
        echo -e "${GREEN}✅ API is healthy${NC}"
        break
    else
        echo "Attempt $i/10: Waiting for API to be ready..."
        sleep 5
    fi
done

echo "Checking UI..."
if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} \
    "curl -s http://localhost:80 | head -20" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ UI is accessible${NC}"
else
    echo -e "${RED}WARNING: UI may not be ready yet${NC}"
fi

################################################################################
# DEPLOYMENT COMPLETE
################################################################################

echo -e "\n${GREEN}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}RELEASE_V2 DEPLOYMENT COMPLETE ✅${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"

echo -e "\n${BLUE}Deployment Summary:${NC}"
echo "  Target Server: ${PRODUCTION_SERVER}"
echo "  Backend Image: stokr-api:${IMAGE_TAG}"
echo "  Frontend Image: stokr-ui:${IMAGE_TAG}"
echo "  API Endpoint: http://${PRODUCTION_SERVER}:8080"
echo "  UI Endpoint: http://${PRODUCTION_SERVER}:80"
echo "  Database Backup: ${BACKUP_PATH}"

echo -e "\n${BLUE}Next Steps:${NC}"
echo "  1. Test login at http://${PRODUCTION_SERVER}:80"
echo "  2. Verify trading features work"
echo "  3. Check admin dashboard"
echo "  4. Monitor logs: ssh ${PRODUCTION_USER}@${PRODUCTION_SERVER} docker-compose logs -f"
echo "  5. If issues occur, rollback available (see ROLLBACK_SCRIPT.sh)"

echo -e "\n${BLUE}Monitoring:${NC}"
echo "  • CPU/Memory: docker stats"
echo "  • Logs: docker-compose logs -f"
echo "  • Health: curl http://${PRODUCTION_SERVER}:8080/api/health"

echo ""
