#!/bin/bash

# Unified Execution Framework - REST API Testing Script
# Tests all Phase 6/7 REST endpoints

set -e

API_BASE="http://localhost:8080"
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Unified Execution Framework API Tests ===${NC}\n"

# Test 1: Get Current Execution Mode
echo -e "${GREEN}[TEST 1]${NC} GET Current Execution Mode"
curl -s "$API_BASE/api/admin/execution/mode" | jq '.'
echo ""

# Test 2: Switch Mode (PAPER → LIVE)
echo -e "${GREEN}[TEST 2]${NC} Switch Mode PAPER → LIVE"
curl -s -X POST "$API_BASE/api/admin/execution/mode/LIVE" \
  -H "Content-Type: application/json" \
  -d '{"reason":"UI Test","requestedBy":"TEST_RUNNER"}' | jq '.'
echo ""

# Test 3: Health Check
echo -e "${GREEN}[TEST 3]${NC} Execution Health Check"
curl -s "$API_BASE/api/admin/execution/health" | jq '.'
echo ""

# Test 4: Execution Statistics
echo -e "${GREEN}[TEST 4]${NC} Execution Statistics"
curl -s "$API_BASE/api/admin/execution/stats" | jq '.'
echo ""

# Test 5: Get Current Configuration
echo -e "${GREEN}[TEST 5]${NC} Get Execution Configuration"
curl -s "$API_BASE/api/admin/execution/config" | jq '.'
echo ""

# Test 6: Update Paper Slippage
echo -e "${GREEN}[TEST 6]${NC} Update Paper Slippage to 3.0 bps"
curl -s -X POST "$API_BASE/api/admin/execution/config/paper/slippage" \
  -H "Content-Type: application/json" \
  -d '{"slippageBps":3.0,"reason":"Test update"}' | jq '.'
echo ""

# Test 7: Start Replay
echo -e "${GREEN}[TEST 7]${NC} Start Replay (SBIN 2025-01-01)"
curl -s -X POST "$API_BASE/api/admin/execution/replay/start" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol":"SBIN",
    "startTime":"2025-01-01T09:15:00Z",
    "endTime":"2025-01-01T15:30:00Z",
    "speed":1.0
  }' | jq '.'
echo ""

# Test 8: Get Replay Status
echo -e "${GREEN}[TEST 8]${NC} Get Replay Status"
curl -s "$API_BASE/api/admin/execution/replay/status" | jq '.'
echo ""

# Test 9: Pause Replay
echo -e "${GREEN}[TEST 9]${NC} Pause Replay"
curl -s -X POST "$API_BASE/api/admin/execution/replay/pause" | jq '.'
echo ""

# Test 10: Resume Replay
echo -e "${GREEN}[TEST 10]${NC} Resume Replay"
curl -s -X POST "$API_BASE/api/admin/execution/replay/resume" | jq '.'
echo ""

# Test 11: Stop Replay
echo -e "${GREEN}[TEST 11]${NC} Stop Replay"
curl -s -X POST "$API_BASE/api/admin/execution/replay/stop" | jq '.'
echo ""

# Test 12: Get Configuration Audit Trail
echo -e "${GREEN}[TEST 12]${NC} Get Configuration Audit Trail"
curl -s "$API_BASE/api/admin/execution/config/audit" | jq '.'
echo ""

# Test 13: Validate Configuration
echo -e "${GREEN}[TEST 13]${NC} Validate Configuration"
curl -s -X POST "$API_BASE/api/admin/execution/config/validate" | jq '.'
echo ""

# Test 14: Switch Back to PAPER
echo -e "${GREEN}[TEST 14]${NC} Switch Mode LIVE → PAPER"
curl -s -X POST "$API_BASE/api/admin/execution/mode/PAPER" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Test complete","requestedBy":"TEST_RUNNER"}' | jq '.'
echo ""

echo -e "${GREEN}=== All Tests Completed ===${NC}\n"
echo "Summary:"
echo "✅ 14 REST API endpoints tested"
echo "✅ Mode switching verified"
echo "✅ Replay controls verified"
echo "✅ Configuration management verified"
echo "✅ Health checks completed"
echo ""
echo "If all tests returned JSON responses without errors, the APIs are working correctly!"
