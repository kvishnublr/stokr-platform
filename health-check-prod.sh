#!/bin/bash

################################################################################
# STOKR PRODUCTION HEALTH CHECK & MONITORING
#
# Purpose: Real-time monitoring of dual deployment
# Usage: sudo ./health-check-prod.sh [interval_seconds]
# Example: sudo ./health-check-prod.sh 10  # Check every 10 seconds
################################################################################

# Configuration
PROD_SERVER="173.249.55.84"
INTERVAL=${1:-15}  # Default 15 seconds

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# ============================================================================
# HEALTH CHECK FUNCTIONS
# ============================================================================

check_service() {
    local url=$1
    local name=$2
    local version=$3

    response=$(curl -s -w "\n%{http_code}" "$url" 2>/dev/null | tail -1)

    if [ "$response" = "200" ]; then
        echo -e "${GREEN}✓${NC} $name ($version): OK"
        return 0
    else
        echo -e "${RED}✗${NC} $name ($version): FAILED (HTTP $response)"
        return 1
    fi
}

check_api() {
    local url=$1

    response=$(curl -s -I "$url" 2>/dev/null | head -1)

    if [[ $response == *"200"* ]] || [[ $response == *"301"* ]] || [[ $response == *"302"* ]]; then
        echo -e "${GREEN}✓${NC} API: CONNECTED"
        return 0
    else
        echo -e "${RED}✗${NC} API: UNREACHABLE"
        return 1
    fi
}

check_latency() {
    local url=$1

    start=$(date +%s%N)
    curl -s "$url" > /dev/null 2>&1
    end=$(date +%s%N)

    latency=$(( ($end - $start) / 1000000 ))

    if [ $latency -lt 100 ]; then
        echo -e "${GREEN}✓${NC} Latency: ${latency}ms (Good)"
    elif [ $latency -lt 500 ]; then
        echo -e "${YELLOW}⚠${NC} Latency: ${latency}ms (Fair)"
    else
        echo -e "${RED}✗${NC} Latency: ${latency}ms (Slow)"
    fi
}

check_disk() {
    usage=$(df / | awk 'NR==2 {print $5}' | sed 's/%//')

    if [ $usage -lt 80 ]; then
        echo -e "${GREEN}✓${NC} Disk Usage: $usage%"
    elif [ $usage -lt 90 ]; then
        echo -e "${YELLOW}⚠${NC} Disk Usage: $usage%"
    else
        echo -e "${RED}✗${NC} Disk Usage: $usage%"
    fi
}

check_memory() {
    usage=$(free | grep Mem | awk '{printf("%.0f", ($3/$2) * 100)}')

    if [ $usage -lt 70 ]; then
        echo -e "${GREEN}✓${NC} Memory Usage: $usage%"
    elif [ $usage -lt 85 ]; then
        echo -e "${YELLOW}⚠${NC} Memory Usage: $usage%"
    else
        echo -e "${RED}✗${NC} Memory Usage: $usage%"
    fi
}

check_nginx() {
    if systemctl is-active --quiet nginx; then
        echo -e "${GREEN}✓${NC} NGINX: RUNNING"
    else
        echo -e "${RED}✗${NC} NGINX: STOPPED"
    fi
}

check_network() {
    if ping -c 1 $PROD_SERVER > /dev/null 2>&1; then
        echo -e "${GREEN}✓${NC} Network: CONNECTED"
    else
        echo -e "${RED}✗${NC} Network: UNREACHABLE"
    fi
}

# ============================================================================
# DISPLAY HEALTH REPORT
# ============================================================================

display_report() {
    clear

    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║         STOKR PRODUCTION HEALTH CHECK & MONITORING             ║"
    echo "║            Dual Deployment Status Report                       ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "Update Interval: ${INTERVAL}s"
    echo ""

    # ========================================================================
    # SYSTEM HEALTH
    # ========================================================================

    echo -e "${CYAN}═══ SYSTEM HEALTH ═══${NC}"
    check_nginx
    check_network
    check_memory
    check_disk
    echo ""

    # ========================================================================
    # EXISTING DESIGN (STABLE)
    # ========================================================================

    echo -e "${CYAN}═══ EXISTING DESIGN (STABLE - DEFAULT) ═══${NC}"
    check_service "http://prod.stokr.in/trader" "Trader Panel" "existing"
    check_service "http://prod.stokr.in/admin" "Admin Panel" "existing"
    check_latency "http://prod.stokr.in/trader"
    echo ""

    # ========================================================================
    # NEW DESIGN (TESTING)
    # ========================================================================

    echo -e "${CYAN}═══ NEW DESIGN (TESTING - ADD ?v=new) ═══${NC}"
    check_service "http://prod.stokr.in/trader?v=new" "Trader Panel" "new"
    check_service "http://prod.stokr.in/admin?v=new" "Admin Panel" "new"
    check_latency "http://prod.stokr.in/trader?v=new"
    echo ""

    # ========================================================================
    # API CONNECTIVITY
    # ========================================================================

    echo -e "${CYAN}═══ API CONNECTIVITY ═══${NC}"
    check_api "http://173.249.55.84:8080/api/health"
    echo ""

    # ========================================================================
    # SERVICE PORTS
    # ========================================================================

    echo -e "${CYAN}═══ SERVICE PORTS ═══${NC}"
    echo "Existing Trader: 8080"
    echo "Existing Admin:  8081"
    echo "New Trader:      8082"
    echo "New Admin:       8083"
    echo "Router:          9090"
    echo ""

    # ========================================================================
    # TEST URLS
    # ========================================================================

    echo -e "${CYAN}═══ TEST URLs ═══${NC}"
    echo "Default (Existing):  https://prod.stokr.in/trader"
    echo "New Design:          https://prod.stokr.in/trader?v=new"
    echo "Old Design (Explicit): https://prod.stokr.in/trader?v=old"
    echo ""

    # ========================================================================
    # QUICK COMMANDS
    # ========================================================================

    echo -e "${CYAN}═══ QUICK COMMANDS ═══${NC}"
    echo "Rollback (if needed): sudo ./rollback-prod.sh"
    echo "View Logs (Nginx):    tail -f /var/log/nginx/stokr-router-error.log"
    echo "View Logs (New):      tail -f /var/log/stokr/new/trader.log"
    echo "Stop monitoring:      Ctrl+C"
    echo ""

    # ========================================================================
    # FOOTER
    # ========================================================================

    echo "═══════════════════════════════════════════════════════════════"
    echo "Next refresh in ${INTERVAL}s... (Press Ctrl+C to exit)"
}

# ============================================================================
# MAIN LOOP
# ============================================================================

main() {
    while true; do
        display_report
        sleep $INTERVAL
    done
}

main "$@"
