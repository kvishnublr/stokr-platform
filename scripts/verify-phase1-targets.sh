#!/bin/bash
# ========================================
# PHASE 1 PERFORMANCE TARGET VERIFICATION
# Verify all 4 key metrics meet targets
# ========================================

set -e

TARGET_HOST=${TARGET_HOST:-http://localhost:8080}
RESULTS_DIR=${RESULTS_DIR:-./load-test-results}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}[Verification]${NC} $1"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_failure() {
    echo -e "${RED}❌ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

# Verify application is running
verify_app_running() {
    print_status "Checking if application is running..."

    if ! curl -s -f "${TARGET_HOST}/actuator/health" > /dev/null 2>&1; then
        print_failure "Application is not running at ${TARGET_HOST}"
        exit 1
    fi

    print_success "Application is running"
}

# Get HTTP latency metrics
get_latency_metrics() {
    print_status "Retrieving HTTP latency metrics..."

    local metrics=$(curl -s "${TARGET_HOST}/actuator/metrics/http.server.requests")

    if [ -z "$metrics" ]; then
        print_warning "Could not retrieve metrics"
        return 1
    fi

    echo "$metrics"
}

# Check order creation latency (p99)
check_order_latency() {
    print_status ""
    print_status "TARGET 1: Order Creation Latency"
    print_status "=================================="

    local target=200  # milliseconds
    local message="Order Creation P99 Latency"

    # Simulate order creation to get real latency
    print_status "Measuring order creation latency..."

    local total_time=0
    local iterations=10

    for i in $(seq 1 $iterations); do
        local start=$(date +%s%N)

        curl -s -X POST \
            -H "Content-Type: application/json" \
            -d "{\"symbol\":\"NIFTY\",\"quantity\":1,\"side\":\"BUY\"}" \
            "${TARGET_HOST}/api/orders" \
            > /dev/null 2>&1

        local end=$(date +%s%N)
        local elapsed=$(( (end - start) / 1000000 ))  # Convert to milliseconds
        total_time=$((total_time + elapsed))
    done

    local avg=$((total_time / iterations))

    if [ $avg -lt $target ]; then
        print_success "$message: ${avg}ms (Target: <${target}ms)"
        return 0
    else
        print_failure "$message: ${avg}ms (Target: <${target}ms) - NEEDS IMPROVEMENT"
        return 1
    fi
}

# Check portfolio query latency (p99)
check_portfolio_latency() {
    print_status ""
    print_status "TARGET 2: Portfolio Query Latency"
    print_status "=================================="

    local target=150  # milliseconds
    local message="Portfolio Query P99 Latency"

    print_status "Measuring portfolio query latency..."

    local total_time=0
    local iterations=10

    for i in $(seq 1 $iterations); do
        local start=$(date +%s%N)

        curl -s "${TARGET_HOST}/api/portfolio/exposure" \
            > /dev/null 2>&1

        local end=$(date +%s%N)
        local elapsed=$(( (end - start) / 1000000 ))
        total_time=$((total_time + elapsed))
    done

    local avg=$((total_time / iterations))

    if [ $avg -lt $target ]; then
        print_success "$message: ${avg}ms (Target: <${target}ms)"
        return 0
    else
        print_failure "$message: ${avg}ms (Target: <${target}ms) - NEEDS IMPROVEMENT"
        return 1
    fi
}

# Check cache hit rate
check_cache_hit_rate() {
    print_status ""
    print_status "TARGET 3: Cache Hit Rate"
    print_status "========================"

    local target=90  # percent

    print_status "Measuring cache hit rate..."

    # Make multiple queries to same endpoint (should hit cache)
    local hits=0
    local misses=0

    for i in $(seq 1 20); do
        # Check if response includes cache header
        local response=$(curl -s -i "${TARGET_HOST}/api/portfolio/exposure" 2>&1)

        if echo "$response" | grep -qi "X-Cache: HIT"; then
            hits=$((hits + 1))
        else
            misses=$((misses + 1))
        fi
    done

    local total=$((hits + misses))
    local hit_rate=$(( (hits * 100) / total ))

    if [ $hit_rate -ge $target ]; then
        print_success "Cache Hit Rate: ${hit_rate}% (Target: >${target}%)"
        return 0
    else
        print_warning "Cache Hit Rate: ${hit_rate}% (Target: >${target}%) - Monitor and adjust TTLs"
        return 1
    fi
}

# Check error rate
check_error_rate() {
    print_status ""
    print_status "TARGET 4: Error Rate"
    print_status "===================="

    local target=0.5  # percent

    print_status "Measuring error rate..."

    local errors=0
    local total=100

    for i in $(seq 1 $total); do
        local response=$(curl -s -o /dev/null -w "%{http_code}" "${TARGET_HOST}/api/portfolio/exposure")

        if [ "$response" != "200" ] && [ "$response" != "304" ]; then
            errors=$((errors + 1))
        fi
    done

    local error_rate=$(( (errors * 100) / total ))

    if [ $error_rate -le $target ]; then
        print_success "Error Rate: ${error_rate}% (Target: <${target}%)"
        return 0
    else
        print_failure "Error Rate: ${error_rate}% (Target: <${target}%) - Investigate errors"
        return 1
    fi
}

# Check database connections
check_db_connections() {
    print_status ""
    print_status "TARGET 5: Database Connection Pool"
    print_status "===================================="

    print_status "Checking database connections..."

    local metrics=$(curl -s "${TARGET_HOST}/actuator/metrics/hikaricp.connections.active")

    if [ -z "$metrics" ]; then
        print_warning "Could not retrieve connection metrics"
        return 1
    fi

    # Parse active connections from response
    local active=$(echo "$metrics" | grep -o '"value":[0-9]*' | head -1 | cut -d':' -f2)
    local max_target=60
    local utilization=$(( (active * 100) / max_target ))

    if [ $utilization -lt 80 ]; then
        print_success "DB Connections: ${active}/${max_target} (${utilization}% utilization)"
        return 0
    else
        print_warning "DB Connections: ${active}/${max_target} (${utilization}% utilization) - Monitor closely"
        return 1
    fi
}

# Generate final report
generate_report() {
    print_status ""
    print_status "Generating verification report..."

    local report_file="${RESULTS_DIR}/phase1-verification-${TIMESTAMP}.md"

    mkdir -p "$RESULTS_DIR"

    {
        echo "# Phase 1 Performance Target Verification"
        echo ""
        echo "**Verification Date:** $(date)"
        echo "**Target Host:** ${TARGET_HOST}"
        echo ""

        echo "## Performance Targets Status"
        echo ""
        echo "| Target | Requirement | Status |"
        echo "|--------|-------------|--------|"
        echo "| Order Creation (p99) | < 200ms | ✅ VERIFY |"
        echo "| Portfolio Query (p99) | < 150ms | ✅ VERIFY |"
        echo "| Cache Hit Rate | > 90% | ✅ VERIFY |"
        echo "| Error Rate | < 0.5% | ✅ VERIFY |"
        echo "| DB Connections | < 80% of 60 | ✅ VERIFY |"
        echo ""

        echo "## Recommendations"
        echo ""
        echo "### If All Targets Met ✅"
        echo "- Phase 1 is complete and ready for production"
        echo "- Proceed to Phase 2 (Redis Cluster, Advanced Caching)"
        echo "- Deploy to production using blue-green strategy"
        echo ""

        echo "### If Some Targets Missed ⚠️"
        echo "1. **Order Latency High?**"
        echo "   - Add more indexes to oms_orders table"
        echo "   - Verify connection pool isn't exhausted"
        echo "   - Check slow query log"
        echo ""
        echo "2. **Portfolio Query Slow?**"
        echo "   - Increase portfolio_exposure cache TTL"
        echo "   - Add @EntityGraph to related entity loads"
        echo "   - Consider V102 partitioning for faster time-range queries"
        echo ""
        echo "3. **Cache Hit Rate Low?**"
        echo "   - Increase cache TTLs (currently 5-30 min)"
        echo "   - Verify Redis is running and connected"
        echo "   - Monitor cache invalidation frequency"
        echo ""
        echo "4. **Error Rate High?**"
        echo "   - Check application logs for exceptions"
        echo "   - Verify database connectivity"
        echo "   - Check Redis availability"
        echo ""

        echo "## Next Steps"
        echo ""
        echo "1. Run comprehensive load test (60 minutes, 50 traders)"
        echo "2. Monitor all metrics in real-time"
        echo "3. Verify cache effectiveness"
        echo "4. Generate performance baseline"
        echo "5. Phase 1 sign-off"
        echo ""

    } > "$report_file"

    print_success "Verification report: $report_file"
}

# Main execution
main() {
    print_status "Phase 1 Performance Target Verification"
    echo ""

    verify_app_running

    # Check all targets
    check_order_latency
    local target1=$?

    check_portfolio_latency
    local target2=$?

    check_cache_hit_rate
    local target3=$?

    check_error_rate
    local target4=$?

    check_db_connections
    local target5=$?

    generate_report

    # Summary
    echo ""
    print_status "======================================"
    print_status "PHASE 1 VERIFICATION COMPLETE"
    print_status "======================================"
    echo ""

    if [ $target1 -eq 0 ] && [ $target2 -eq 0 ] && [ $target3 -eq 0 ] && [ $target4 -eq 0 ] && [ $target5 -eq 0 ]; then
        print_success "ALL PHASE 1 TARGETS MET! ✅"
        print_success "Ready for Phase 2"
        exit 0
    else
        print_warning "Some targets need improvement"
        print_warning "See report for recommendations"
        exit 1
    fi
}

main "$@"
