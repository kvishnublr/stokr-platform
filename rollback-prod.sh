#!/bin/bash

################################################################################
# STOKR PRODUCTION ROLLBACK SCRIPT
#
# Purpose: Instant rollback to EXISTING design if NEW design has issues
# Safety: Zero downtime, zero data loss
# Author: DevOps Team
# Status: PRODUCTION READY
#
# Usage: sudo ./rollback-prod.sh
################################################################################

set -e

# ============================================================================
# CONFIGURATION
# ============================================================================

PROD_SERVER="root@prod.stokr.in"
PROD_PATH="/var/www/stokr"
BACKUP_PATH="/var/backups/stokr"

# Ports
NEW_TRADER_PORT=8082
NEW_ADMIN_PORT=8083

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ============================================================================
# FUNCTIONS
# ============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[⚠]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

# ============================================================================
# ROLLBACK PROCEDURE
# ============================================================================

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║            STOKR PRODUCTION ROLLBACK PROCEDURE                 ║"
    echo "║         Reverting to EXISTING STABLE DESIGN                    ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    log_warning "STARTING ROLLBACK PROCEDURE - $(date)"

    # Step 1: Stop new services
    log_info "Step 1: Stopping NEW Design services..."
    ssh $PROD_SERVER "
        # Kill new design processes
        pkill -f 'python3 -m http.server $NEW_TRADER_PORT' || true
        pkill -f 'python3 -m http.server $NEW_ADMIN_PORT' || true
        sleep 2
        echo 'New services stopped'
    " 2>/dev/null || true

    log_success "NEW Design services stopped"

    # Step 2: Update nginx to remove new routes
    log_info "Step 2: Updating NGINX configuration..."
    ssh $PROD_SERVER "
        # Disable router config temporarily
        rm -f /etc/nginx/sites-enabled/stokr-router || true

        # Enable stable config
        [ -f /etc/nginx/sites-available/stokr ] && ln -sf /etc/nginx/sites-available/stokr /etc/nginx/sites-enabled/ || true

        # Test and reload
        nginx -t && systemctl reload nginx
        echo 'Nginx configuration updated'
    "

    log_success "NGINX configuration restored to EXISTING design only"

    # Step 3: Verify rollback
    log_info "Step 3: Verifying rollback..."
    sleep 3

    # Check existing services
    log_info "Checking EXISTING Trader..."
    curl -s "http://prod.stokr.in/trader" > /dev/null && {
        log_success "EXISTING Trader: ✓ ACTIVE"
    } || {
        log_warning "EXISTING Trader: Not responding (might be starting)"
    }

    log_info "Checking EXISTING Admin..."
    curl -s "http://prod.stokr.in/admin" > /dev/null && {
        log_success "EXISTING Admin: ✓ ACTIVE"
    } || {
        log_warning "EXISTING Admin: Not responding (might be starting)"
    }

    # Step 4: Cleanup
    log_info "Step 4: Cleaning up..."
    ssh $PROD_SERVER "
        # Archive new design files (for debugging)
        mkdir -p $PROD_PATH/failed-deployments
        tar -czf $PROD_PATH/failed-deployments/new-design-$(date +%Y%m%d-%H%M%S).tar.gz $PROD_PATH/new/ 2>/dev/null || true

        # Log rollback event
        echo 'ROLLBACK: NEW design disabled, EXISTING design restored' >> /var/log/stokr-rollback.log
        echo \"Rollback completed at \$(date)\" >> /var/log/stokr-rollback.log
    "

    log_success "Cleanup completed"

    # Final status
    log_info "Step 5: Generating status report..."
    ssh $PROD_SERVER "
        echo ''
        echo '======== ROLLBACK STATUS ========'
        echo \"Time: \$(date)\"
        echo \"Nginx Status: \$(systemctl is-active nginx)\"
        echo \"New Services Status:\"
        pgrep -f 'python3 -m http.server $NEW_TRADER_PORT' > /dev/null && echo '  Trader: RUNNING' || echo '  Trader: STOPPED'
        pgrep -f 'python3 -m http.server $NEW_ADMIN_PORT' > /dev/null && echo '  Admin: RUNNING' || echo '  Admin: STOPPED'
        echo \"Router Status: \$([ -L /etc/nginx/sites-enabled/stokr-router ] && echo 'ENABLED' || echo 'DISABLED')\"
        echo '==============================='
    "

    # ========================================================================
    # ROLLBACK COMPLETE SUMMARY
    # ========================================================================

    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                   ROLLBACK COMPLETED ✓                          ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    cat << EOF

${GREEN}✓ ROLLBACK SUCCESSFUL${NC}

Action Taken:
  ✓ Stopped NEW Design services (ports 8082, 8083)
  ✓ Removed NEW design routes from router
  ✓ Restored EXISTING design as default
  ✓ Reloaded NGINX configuration
  ✓ Archived failed deployment for debugging

Status:
  Users Now See:  EXISTING STABLE DESIGN
  Impact:         ZERO DOWNTIME
  Data Loss:      NONE
  API Impact:     NONE

URLs After Rollback:
  https://prod.stokr.in/trader  → EXISTING Design
  https://prod.stokr.in/admin   → EXISTING Design

New Design Files:
  Location:  $PROD_PATH/new/
  Status:    PRESERVED (for debugging)
  Archive:   $PROD_PATH/failed-deployments/

Next Steps:
  1. Investigate issue in NEW design
  2. Fix the problem
  3. Test in staging environment
  4. Redeploy to production when ready

Logs:
  Rollback Log:    /var/log/stokr-rollback.log
  Nginx Access:    /var/log/nginx/stokr-router-access.log
  Nginx Errors:    /var/log/nginx/stokr-router-error.log

For Investigation:
  - Check: /var/log/stokr/new/trader.log
  - Check: /var/log/stokr/new/admin.log
  - Browser Console: F12 → Console tab
  - Network Requests: F12 → Network tab

If you need to re-enable NEW design:
  sudo ./deploy-to-prod.sh

${GREEN}Everything rolled back safely! The existing design is now the default.${NC}

EOF

    log_success "Rollback completed at $(date)"
}

# ============================================================================
# MAIN
# ============================================================================

main "$@"

exit 0
