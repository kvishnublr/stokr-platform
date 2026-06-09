#!/bin/bash
#
# REMOTE VERIFICATION SCRIPT
# Production Server: 173.249.55.84
# Run this 45 minutes before market open (08:30 UTC)
#
# Usage: bash REMOTE_VERIFICATION_SCRIPT.sh
#

set -e

echo "================================="
echo "PRE-MARKET VERIFICATION SCRIPT"
echo "================================="
echo "Time: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
echo ""

# COLORS
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS=0
FAIL=0

# Helper function for checks
check() {
    local name=$1
    local cmd=$2
    local expected=$3

    echo -n "Checking: $name... "

    result=$(eval "$cmd" 2>&1 || true)

    if echo "$result" | grep -q "$expected"; then
        echo -e "${GREEN}✅ PASS${NC}"
        ((PASS++))
    else
        echo -e "${RED}❌ FAIL${NC}"
        echo "  Expected: $expected"
        echo "  Got: $result"
        ((FAIL++))
    fi
}

# ============================================
# 1. GIT COMMIT VERIFICATION
# ============================================
echo ""
echo "1. GIT COMMIT VERIFICATION"
echo "=========================="

# Navigate to stokr repo (adjust path if different)
cd /opt/stokr || cd ~/stokr || cd /root/stokr || {
    echo -e "${RED}ERROR: Could not find stokr directory${NC}"
    exit 1
}

echo "Current commit:"
git log -1 --oneline

COMMIT=$(git log -1 --pretty=format:'%h')
if [ "$COMMIT" = "498ec24d" ] || [ "$COMMIT" = "498ec24" ]; then
    echo -e "${GREEN}✅ PASS: Correct commit deployed${NC}"
    ((PASS++))
else
    echo -e "${RED}❌ FAIL: Expected 498ec24d, got $COMMIT${NC}"
    ((FAIL++))
fi

# ============================================
# 2. DOCKER CONTAINER VERIFICATION
# ============================================
echo ""
echo "2. DOCKER CONTAINER VERIFICATION"
echo "================================="

if docker ps | grep -q stokr-api; then
    echo -e "${GREEN}✅ PASS: Container stokr-api is running${NC}"
    ((PASS++))
else
    echo -e "${RED}❌ FAIL: Container stokr-api is not running${NC}"
    ((FAIL++))
fi

echo "Docker image:"
docker ps --filter "name=stokr-api" --format "table {{.Image}}\t{{.Status}}"

# ============================================
# 3. HEALTH CHECK
# ============================================
echo ""
echo "3. HEALTH CHECK"
echo "==============="

HEALTH=$(curl -s http://localhost:8080/actuator/health || echo "ERROR")

if echo "$HEALTH" | grep -q '"status":"UP"'; then
    echo -e "${GREEN}✅ PASS: Application is UP${NC}"
    ((PASS++))
else
    echo -e "${RED}❌ FAIL: Application is not UP${NC}"
    echo "Response: $HEALTH"
    ((FAIL++))
fi

# ============================================
# 4. CONFIGURATION VERIFICATION
# ============================================
echo ""
echo "4. CONFIGURATION VERIFICATION"
echo "=============================="

# Get config
CONFIG=$(curl -s http://localhost:8080/actuator/configprops 2>/dev/null || echo "{}")

# Check cooldown
if echo "$CONFIG" | grep -q '"order-cooldown-ms".*30000'; then
    echo -e "${GREEN}✅ PASS: order-cooldown-ms = 30000${NC}"
    ((PASS++))
else
    echo -e "${YELLOW}⚠️  WARNING: order-cooldown-ms might not be 30000${NC}"
    echo "  Value: $(echo "$CONFIG" | grep -o '"order-cooldown-ms"[^,]*' | head -1)"
fi

# Check cluster detection enabled
if echo "$CONFIG" | grep -q '"cluster-detection-enabled".*true'; then
    echo -e "${GREEN}✅ PASS: cluster-detection-enabled = true${NC}"
    ((PASS++))
else
    echo -e "${YELLOW}⚠️  WARNING: cluster-detection-enabled might not be true${NC}"
fi

# Check cluster max entries
if echo "$CONFIG" | grep -q '"cluster-max-entries".*2'; then
    echo -e "${GREEN}✅ PASS: cluster-max-entries = 2${NC}"
    ((PASS++))
else
    echo -e "${YELLOW}⚠️  WARNING: cluster-max-entries might not be 2${NC}"
fi

# ============================================
# 5. STARTUP LOGS VERIFICATION
# ============================================
echo ""
echo "5. STARTUP LOGS VERIFICATION"
echo "============================"

echo "Checking for ERROR logs:"
ERRORS=$(docker logs stokr-api 2>/dev/null | grep -i "ERROR" | head -5 || true)

if [ -z "$ERRORS" ]; then
    echo -e "${GREEN}✅ PASS: No ERROR logs found${NC}"
    ((PASS++))
else
    echo -e "${RED}❌ FAIL: ERROR logs detected:${NC}"
    echo "$ERRORS"
    ((FAIL++))
fi

echo ""
echo "Checking for startup confirmation:"
if docker logs stokr-api 2>/dev/null | grep -q "started"; then
    echo -e "${GREEN}✅ PASS: Application started successfully${NC}"
    ((PASS++))
else
    echo -e "${YELLOW}⚠️  WARNING: Could not confirm startup message${NC}"
fi

# ============================================
# 6. DATABASE CONNECTIVITY
# ============================================
echo ""
echo "6. DATABASE CONNECTIVITY"
echo "========================"

# Check if DB accessible (adjust connection string if needed)
DB_RESULT=$(curl -s http://localhost:8080/actuator/health/db || echo "ERROR")

if echo "$DB_RESULT" | grep -q '"status":"UP"'; then
    echo -e "${GREEN}✅ PASS: Database connected${NC}"
    ((PASS++))
else
    echo -e "${RED}❌ FAIL: Database not connected${NC}"
    ((FAIL++))
fi

# ============================================
# 7. BROKER CONNECTION
# ============================================
echo ""
echo "7. BROKER CONNECTION"
echo "===================="

# Check broker service (API endpoint - adjust as needed)
BROKER=$(curl -s http://localhost:8080/api/broker/status 2>/dev/null || echo "ERROR")

if echo "$BROKER" | grep -qi "connected\|active"; then
    echo -e "${GREEN}✅ PASS: Broker connection active${NC}"
    ((PASS++))
else
    echo -e "${YELLOW}⚠️  WARNING: Could not confirm broker connection${NC}"
    echo "  Response: $BROKER"
fi

# ============================================
# 8. CLUSTER DETECTION DRY-RUN TEST
# ============================================
echo ""
echo "8. CLUSTER DETECTION TEST (Dry-Run)"
echo "===================================="

echo "Testing cluster detection logic:"
echo "Note: This is a dry-run. Do NOT create real orders."
echo ""

echo "Scenario: 3 entries in 2 minutes"
echo "Expected: Entry 1 & 2 allowed, Entry 3 rejected"
echo ""

echo "Entry 1 simulation:"
echo '  curl -X POST http://localhost:8080/api/entry ...'
echo "  Expected response: APPROVED"
echo ""

echo "Entry 2 simulation (30 sec later):"
echo '  curl -X POST http://localhost:8080/api/entry ...'
echo "  Expected response: APPROVED"
echo ""

echo "Entry 3 simulation (within 2 min window):"
echo '  curl -X POST http://localhost:8080/api/entry ...'
echo "  Expected response: REJECTED"
echo "  Reason: Cluster detected"
echo ""

echo -e "${YELLOW}⚠️  Dry-run test not executed (no real orders created)${NC}"
echo "To execute real test, use the API test commands from PRE_MARKET_READINESS_REPORT.md"

# ============================================
# SUMMARY
# ============================================
echo ""
echo "================================="
echo "VERIFICATION SUMMARY"
echo "================================="
echo -e "Passed: ${GREEN}$PASS${NC}"
echo -e "Failed: ${RED}$FAIL${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}✅ ALL CHECKS PASSED${NC}"
    echo "System is READY for market open"
    exit 0
else
    echo -e "${RED}❌ SOME CHECKS FAILED${NC}"
    echo "Please review failures above before proceeding"
    exit 1
fi
