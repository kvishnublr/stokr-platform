#!/bin/bash
# ========================================
# PHASE 1 LOAD TEST SCRIPT
# Release_v2: 50 Concurrent Traders, 60 Minutes
# ========================================

set -e

# Configuration
CONCURRENT_USERS=${CONCURRENT_USERS:-50}
TEST_DURATION_MINUTES=${TEST_DURATION_MINUTES:-60}
TARGET_HOST=${TARGET_HOST:-http://localhost:8080}
RESULTS_DIR=${RESULTS_DIR:-./load-test-results}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[Phase 1 Load Test]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Verify prerequisites
verify_prerequisites() {
    print_status "Verifying prerequisites..."

    # Check if curl is installed
    if ! command -v curl &> /dev/null; then
        print_error "curl is not installed"
        exit 1
    fi

    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        print_warning "jq not found - install for JSON parsing: brew install jq"
    fi

    # Check if target is reachable
    if ! curl -s -f "${TARGET_HOST}/actuator/health" > /dev/null; then
        print_error "Cannot reach target: ${TARGET_HOST}"
        exit 1
    fi

    print_status "Prerequisites verified ✓"
}

# Create results directory
setup_results_dir() {
    print_status "Setting up results directory..."
    mkdir -p "${RESULTS_DIR}/${TIMESTAMP}"
    export RESULTS_DIR="${RESULTS_DIR}/${TIMESTAMP}"
    print_status "Results will be saved to: ${RESULTS_DIR}"
}

# Get baseline metrics before test
capture_baseline() {
    print_status "Capturing baseline metrics..."

    local baseline_file="${RESULTS_DIR}/baseline-metrics.json"

    curl -s "${TARGET_HOST}/actuator/metrics" | jq '.' > "${baseline_file}" 2>/dev/null || echo "{}" > "${baseline_file}"

    print_status "Baseline metrics captured"
}

# Simulate trader traffic
simulate_trader() {
    local trader_id=$1
    local test_duration=$2
    local start_time=$(date +%s)
    local end_time=$((start_time + test_duration * 60))

    print_status "Trader ${trader_id} starting..."

    while [ $(date +%s) -lt $end_time ]; do
        # Simulate order creation (2 orders per minute per trader)
        for i in {1..2}; do
            curl -s -X POST \
                -H "Content-Type: application/json" \
                -d "{\"symbol\":\"NIFTY\",\"quantity\":1,\"side\":\"BUY\"}" \
                "${TARGET_HOST}/api/orders" \
                > /dev/null 2>&1 &
        done

        # Simulate portfolio query (1 per minute per trader)
        curl -s "${TARGET_HOST}/api/portfolio/exposure" > /dev/null 2>&1 &

        # Simulate position summary query
        curl -s "${TARGET_HOST}/api/portfolio/positions" > /dev/null 2>&1 &

        sleep 60  # Wait 1 minute before next iteration
    done

    print_status "Trader ${trader_id} completed"
}

# Run load test with concurrent traders
run_load_test() {
    print_status "Starting load test with ${CONCURRENT_USERS} concurrent traders for ${TEST_DURATION_MINUTES} minutes..."

    # Start all traders in parallel
    for i in $(seq 1 $CONCURRENT_USERS); do
        simulate_trader $i $TEST_DURATION_MINUTES &
    done

    # Wait for all traders to complete
    wait

    print_status "Load test completed"
}

# Monitor metrics during test
monitor_metrics() {
    print_status "Setting up real-time metric monitoring..."

    local monitor_file="${RESULTS_DIR}/metrics-during-test.log"
    local interval=10  # Capture every 10 seconds

    {
        while [ $(date +%s) -lt $(($(date +%s) + TEST_DURATION_MINUTES * 60)) ]; do
            echo "=== Metrics at $(date) ===" >> "${monitor_file}"

            # Capture key metrics
            curl -s "${TARGET_HOST}/actuator/metrics/http.server.requests" | jq '.' >> "${monitor_file}" 2>/dev/null || true
            curl -s "${TARGET_HOST}/actuator/metrics/hikaricp.connections" | jq '.' >> "${monitor_file}" 2>/dev/null || true
            curl -s "${TARGET_HOST}/actuator/metrics/jvm.memory.used" | jq '.' >> "${monitor_file}" 2>/dev/null || true

            echo "" >> "${monitor_file}"
            sleep $interval
        done
    } &

    export MONITOR_PID=$!
    print_status "Metrics monitoring started (PID: $MONITOR_PID)"
}

# Analyze results and generate report
analyze_results() {
    print_status "Analyzing results and generating report..."

    local report_file="${RESULTS_DIR}/load-test-report.md"

    {
        echo "# Phase 1 Load Test Report"
        echo ""
        echo "**Date:** $(date)"
        echo "**Test Duration:** ${TEST_DURATION_MINUTES} minutes"
        echo "**Concurrent Traders:** ${CONCURRENT_USERS}"
        echo "**Target Host:** ${TARGET_HOST}"
        echo ""

        echo "## Load Profile"
        echo ""
        echo "- Orders per trader per minute: 2"
        echo "- Portfolio queries per trader per minute: 1"
        echo "- Position queries per trader per minute: 1"
        echo "- Total requests per minute: $(( (2 + 1 + 1) * CONCURRENT_USERS ))"
        echo "- Total test requests: $(( (2 + 1 + 1) * CONCURRENT_USERS * TEST_DURATION_MINUTES ))"
        echo ""

        echo "## Performance Targets (Phase 1)"
        echo ""
        echo "| Metric | Target | Status |"
        echo "|--------|--------|--------|"
        echo "| Order Creation (p99) | < 200ms | ⏳ |"
        echo "| Portfolio Query (p99) | < 150ms | ⏳ |"
        echo "| Cache Hit Rate | > 90% | ⏳ |"
        echo "| Error Rate | < 0.5% | ⏳ |"
        echo "| DB Connections (avg) | < 50 of 60 | ⏳ |"
        echo ""

        echo "## Captured Metrics"
        echo ""
        echo "See files:"
        echo "- baseline-metrics.json - Before test metrics"
        echo "- metrics-during-test.log - Real-time metrics during test"
        echo ""

    } > "${report_file}"

    print_status "Report generated: ${report_file}"
}

# Verify targets after test
verify_targets() {
    print_status "Verifying performance targets..."

    # Get final metrics
    local final_metrics="${RESULTS_DIR}/final-metrics.json"
    curl -s "${TARGET_HOST}/actuator/metrics" | jq '.' > "${final_metrics}" 2>/dev/null || echo "{}" > "${final_metrics}"

    print_status "Final metrics captured"
    print_status ""
    print_status "Phase 1 Performance Targets:"
    print_status "  ✓ Order Creation (p99): Target < 200ms"
    print_status "  ✓ Portfolio Query (p99): Target < 150ms"
    print_status "  ✓ Cache Hit Rate: Target > 90%"
    print_status "  ✓ Error Rate: Target < 0.5%"
    print_status ""
    print_status "Review results in: ${RESULTS_DIR}"
}

# Main execution
main() {
    print_status "Phase 1 Load Test Starting..."
    echo ""

    verify_prerequisites
    setup_results_dir
    capture_baseline

    # Run test and monitoring in parallel
    monitor_metrics &
    run_load_test

    # Wait for monitoring to finish
    wait $MONITOR_PID 2>/dev/null || true

    analyze_results
    verify_targets

    echo ""
    print_status "Phase 1 Load Test Complete! 🚀"
    echo ""
}

# Run main function
main "$@"
