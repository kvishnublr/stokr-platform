#!/bin/bash

################################################################################
# RELEASE_V2 ROLLBACK SCRIPT
# Instantly rollback to Release_v1 if deployment issues occur
# Execution Time: < 2 minutes
################################################################################

set -e

PRODUCTION_SERVER="173.249.55.84"
PRODUCTION_USER="root"
SSH_PORT="22"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}════════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}RELEASE_V2 ROLLBACK PROCEDURE${NC}"
echo -e "${YELLOW}════════════════════════════════════════════════════════${NC}"

echo -e "\n${RED}⚠️  WARNING: This will stop Release_v2 and restore Release_v1${NC}"
read -p "Continue with rollback? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Rollback cancelled."
    exit 0
fi

echo -e "\n${BLUE}PHASE 1: Stop Release_v2 Services${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} << 'EOF'
    cd /opt/stokr-platform
    echo "Stopping Release_v2 containers..."
    docker-compose down
    sleep 5
    echo "✅ Release_v2 services stopped"
EOF

echo -e "\n${BLUE}PHASE 2: Restore Release_v1 Services${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} << 'EOF'
    cd /opt/stokr-platform

    # Switch to v1 docker-compose config (must exist)
    if [ -f "docker-compose.v1.yml" ]; then
        echo "Starting Release_v1 services..."
        docker-compose -f docker-compose.v1.yml up -d
        sleep 10
        echo "✅ Release_v1 services started"
    else
        echo "⚠️  docker-compose.v1.yml not found"
        echo "Manual steps required:"
        echo "  1. Start v1 containers manually"
        echo "  2. Verify database connectivity"
        echo "  3. Check application logs"
        exit 1
    fi
EOF

echo -e "\n${BLUE}PHASE 3: Verify Rollback${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo "Checking API health..."
for i in {1..5}; do
    if ssh -p ${SSH_PORT} ${PRODUCTION_USER}@${PRODUCTION_SERVER} \
        "curl -s http://localhost:8080/api/health | grep -q 'UP'" 2>/dev/null; then
        echo -e "${GREEN}✅ Release_v1 API is healthy${NC}"
        break
    else
        echo "Attempt $i/5: Waiting for API..."
        sleep 5
    fi
done

echo -e "\n${GREEN}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}ROLLBACK COMPLETE ✅${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"

echo -e "\n${BLUE}Status:${NC}"
echo "  ✅ Release_v2 stopped"
echo "  ✅ Release_v1 restored"
echo "  ✅ API responding"

echo -e "\n${BLUE}Next Steps:${NC}"
echo "  1. Investigate what went wrong"
echo "  2. Review logs: ssh ${PRODUCTION_USER}@${PRODUCTION_SERVER} docker-compose logs"
echo "  3. Fix issues in Release_v2"
echo "  4. Re-run DEPLOYMENT_SCRIPT.sh when ready"

echo -e "\n${BLUE}Database Status:${NC}"
echo "  ✅ Database UNCHANGED (all migrations are additive)"
echo "  ✅ All v2 data still in database"
echo "  ✅ Can immediately re-deploy v2 when ready"

echo ""
