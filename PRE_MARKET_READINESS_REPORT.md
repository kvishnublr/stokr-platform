# 📋 PRE-MARKET READINESS VERIFICATION REPORT
## Live Deployment Verification - 2026-06-09 08:00 UTC

**Market Open**: 09:15 UTC (Expected)  
**Report Time**: [WILL BE GENERATED AT DEPLOYMENT TIME]  
**Status**: [PENDING - RUN VERIFICATION SCRIPT]

---

# PART 1: LOCAL VERIFICATION ✅ (COMPLETE)

## ✅ Git Commit Verification

```
Expected:  498ec24d
Actual:    498ec24d OPTIMIZE: Remove redundant filter in cluster detection
Status:    ✅ PASS - Correct commit deployed
```

## ✅ Configuration File Check

```
FILE: stokr-bootstrap/src/main/resources/application.yml

CONFIG CHECK:
├─ order-cooldown-ms: ${STOKR_RISK_ORDER_COOLDOWN_MS:30000}
│  Expected: 30000 (or env override)
│  Status: ✅ PASS
│
├─ cluster-detection-enabled: ${STOKR_RISK_CLUSTER_DETECTION_ENABLED:true}
│  Expected: true (or env override)
│  Status: ✅ PASS
│
├─ cluster-max-entries: ${STOKR_RISK_CLUSTER_MAX_ENTRIES:2}
│  Expected: 2
│  Status: ✅ PASS
│
├─ cluster-detection-window-minutes: ${STOKR_RISK_CLUSTER_DETECTION_WINDOW_MINUTES:2}
│  Expected: 2
│  Status: ✅ PASS
│
└─ Source Configuration: ✅ VERIFIED IN SOURCE
```

---

# PART 2: REMOTE DEPLOYMENT VERIFICATION ⚠️ (REQUIRES SERVER ACCESS)

## Instructions to Verify on Production Server (173.249.55.84)

**Run this script on the production server:**

```bash
#!/bin/bash
# PRE-MARKET VERIFICATION SCRIPT
# Run on production server: 173.249.55.84

echo "=== PRE-MARKET READINESS VERIFICATION ==="
echo "Time: $(date -u)"
echo ""

# 1. GIT COMMIT
echo "1. GIT COMMIT VERIFICATION"
echo "Expected: 498ec24d"
echo "Actual:"
git -C /path/to/stokr log -1 --oneline
echo ""

# 2. DOCKER IMAGE
echo "2. DOCKER IMAGE VERIFICATION"
echo "Running Container:"
docker ps | grep stokr-api
echo ""
echo "Image Details:"
docker inspect stokr-api --format='{{.Image}}'
docker inspect stokr-api --format='{{.Created}}'
echo ""

# 3. RUNTIME CONFIGURATION
echo "3. RUNTIME CONFIGURATION"
echo "Testing actuator endpoint:"
curl -s http://localhost:8080/actuator/configprops | grep -A 50 '"stokr"' | head -100
echo ""

# 4. STARTUP LOGS
echo "4. STARTUP LOGS (Last 50 lines)"
docker logs stokr-api | tail -50
echo ""

# 5. CONFIGURATION VALUES
echo "5. EXTRACTED CONFIGURATION VALUES"
echo "Cooldown MS:"
curl -s http://localhost:8080/actuator/configprops | jq '.[] | select(.name | contains("stokr")) | .properties | select(.["order-cooldown-ms"] != null) | .["order-cooldown-ms"]'
echo ""
echo "Cluster Detection:"
curl -s http://localhost:8080/actuator/configprops | jq '.[] | select(.name | contains("stokr")) | .properties | select(.["cluster-detection-enabled"] != null) | .["cluster-detection-enabled"]'
echo ""

# 6. HEALTH CHECK
echo "6. HEALTH CHECK"
curl -s http://localhost:8080/actuator/health | jq '.'
echo ""

echo "=== VERIFICATION COMPLETE ==="
```

---

# PART 3: DRY-RUN TEST SCENARIOS

## Scenario A: Cluster Detection (3 Entries in 2 Minutes)

**Test Plan:**
```
Step 1: Submit FIRST entry order
├─ Expected: APPROVED ✅
├─ Check: No rejection message
└─ Log: "ORDER_ACCEPTED"

Step 2: Wait 30 seconds

Step 3: Submit SECOND entry order
├─ Expected: APPROVED ✅
├─ Check: No rejection message
└─ Log: "ORDER_ACCEPTED"

Step 4: Wait 30 seconds

Step 5: Submit THIRD entry order (within 2-min window)
├─ Expected: REJECTED ❌
├─ Check: Rejection reason = "Cluster detected"
├─ Log: "cluster.detection.triggered"
└─ Message: "Cluster detected: 3 entries in 2 min (max 2 allowed)"
```

**API Test Command:**
```bash
# Execute on production server:

# Entry 1
curl -X POST http://localhost:8080/api/entry \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "HCLTECH",
    "quantity": 1,
    "side": "BUY",
    "testMode": true
  }' | jq '.status'

sleep 30

# Entry 2
curl -X POST http://localhost:8080/api/entry \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "TECHM",
    "quantity": 1,
    "side": "BUY",
    "testMode": true
  }' | jq '.status'

sleep 30

# Entry 3 (should be rejected)
curl -X POST http://localhost:8080/api/entry \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "NTPC",
    "quantity": 1,
    "side": "BUY",
    "testMode": true
  }' | jq '.'
```

**Expected Output for Entry 3:**
```json
{
  "status": "REJECTED",
  "reason": "CLUSTER_DETECTION",
  "message": "Cluster detected: 3 entries in 2 min (max 2 allowed)"
}
```

---

## Scenario B: Manual Broker Exit & Signal Cleanup

**Test Plan:**
```
Step 1: Create position via API
├─ Symbol: SUNPHARMA
├─ Quantity: 1
├─ Expected: Position created
└─ Log: Signal ID recorded

Step 2: Verify signal is RUNNING
├─ Query: SELECT outcome_status FROM strategy_signals WHERE id='{signal_id}'
├─ Expected: outcome_status = 'RUNNING'
└─ Log: Signal is active

Step 3: Manually close position in Zerodha
├─ Broker terminal: Sell 1 SUNPHARMA
├─ Expected: Broker position qty = 0
└─ Time: Note exact timestamp (T+0)

Step 4: Wait for broker sync
├─ Wait: 5 seconds
├─ Process: BrokerPositionTruthService.syncUser() detects mismatch
└─ Log: Watch for "broker.truth.external_exit" message

Step 5: Verify signal outcome auto-updated
├─ Query: SELECT outcome_status FROM strategy_signals WHERE id='{signal_id}'
├─ Expected: outcome_status = 'CLOSED'
├─ Comment: "Position manually closed at broker (MANUAL_BROKER_EXIT)"
├─ outcome_time: Should be recent (within 10 seconds of manual close)
└─ Log: "signal.outcome.auto_updated"

Step 6: Verify no duplicate exit order
├─ Query: SELECT COUNT(*) FROM oms_orders WHERE signal_id='{signal_id}' AND side='SELL'
├─ Expected: Exactly 1 SELL order (from manual exit detection)
├─ NOT 2+ orders (would indicate duplicate exit bug)
└─ Status: All FILLED (from broker)

Step 7: Verify UI reflects closed position
├─ Dashboard: Position no longer showing
├─ History: Shows manual exit at broker price
└─ Outcome: Shows "CLOSED"
```

**Database Test Commands:**
```sql
-- Check signal is RUNNING before manual exit
SELECT id, symbol, outcome_status, outcome_time, outcome_comment
FROM strategy_signals
WHERE symbol = 'SUNPHARMA'
ORDER BY created_at DESC
LIMIT 1;

-- After manual exit, should show CLOSED
SELECT id, symbol, outcome_status, outcome_time, outcome_comment
FROM strategy_signals
WHERE symbol = 'SUNPHARMA'
ORDER BY created_at DESC
LIMIT 1;

-- Verify no duplicate exit orders
SELECT COUNT(*), side, state
FROM oms_orders
WHERE signal_id = '{SIGNAL_ID}'
GROUP BY side, state;
```

---

# PART 4: LOG VERIFICATION CHECKLIST

**Expected Log Messages (within 5 minutes of deployment):**

```
✅ Startup Messages:
├─ "Spring started in X ms"
├─ "stokr-platform started"
├─ "Actuator available at http://..."
└─ No ERROR logs

✅ Configuration Loaded:
├─ "order-cooldown-ms: 30000"
├─ "cluster-detection-enabled: true"
├─ "cluster-max-entries: 2"
└─ "cluster-detection-window-minutes: 2"

✅ Services Started:
├─ "OrderCooldownRule registered"
├─ "ClusterDetectionRule registered"
├─ "BrokerPositionTruthService started"
└─ "StrategySignalRepository initialized"

✅ Ready for Trading:
├─ "RiskEngineService ready"
├─ "Broker connection active"
└─ "Ready for orders"
```

**Check Logs:**
```bash
docker logs stokr-api | grep -E "ERROR|WARN|order-cooldown|cluster-detection"
```

---

# PART 5: PRE-MARKET VERIFICATION CHECKLIST

## VERIFICATION STEPS (Run at 08:30 UTC, 45 min before market open)

| # | Check | Command | Expected | Status |
|---|-------|---------|----------|--------|
| 1 | Git Commit | `git log -1 --oneline` | 498ec24d | ☐ |
| 2 | Docker Container Running | `docker ps \| grep stokr-api` | RUNNING | ☐ |
| 3 | Health Check | `curl http://localhost:8080/actuator/health` | UP | ☐ |
| 4 | Configuration: Cooldown | Actuator configprops | 30000 ms | ☐ |
| 5 | Configuration: Cluster Enabled | Actuator configprops | true | ☐ |
| 6 | Configuration: Max Entries | Actuator configprops | 2 | ☐ |
| 7 | Startup Logs Clean | `docker logs stokr-api \| grep ERROR` | (empty) | ☐ |
| 8 | Broker Connection | Zerodha auth token | Valid | ☐ |
| 9 | Database Connection | SELECT 1 FROM strategy_signals | (1 result) | ☐ |
| 10 | Cluster Detection Test | 3 entries in 2 min | 3rd rejected | ☐ |

---

# PART 6: TEST TRADE AT MARKET OPEN (9:15 UTC)

**Objective**: Verify signal generation and execution are working after deployment.

**Test Signal Specifications**:
```
TEST_TRADE_MARKETOPEN:
├─ Time: 09:15 UTC (market open + 0-5 seconds)
├─ Symbol: SBIN (SBI Bank - cheapest NIFTY50 stock, ~600 INR per share)
├─ Strategy: INDEX_HUNT (verify quality gates working)
├─ Test Mode: YES (test-trade flag = true)
├─ Quantity: 1 (minimum for testing)
├─ Expected:
│  ├─ Signal generated at 09:15
│  ├─ Quality score > 75 (new gate)
│  ├─ Entry executed within 5 seconds
│  ├─ Position created
│  ├─ No cluster rejection
│  └─ Exit triggered within 2-5 minutes
└─ Success Criteria:
   ├─ Signal lifecycle: PENDING → RUNNING → CLOSED
   ├─ Position properly tracked
   └─ Outcome recorded in database
```

**Verification After Test Trade**:
```bash
# Check signal was created and completed
SELECT 
    id,
    symbol,
    created_at,
    entry_price,
    exit_price,
    outcome_status,
    realized_pnl,
    test_trade
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
ORDER BY created_at DESC
LIMIT 1;

# Expected:
# id: [signal_id]
# symbol: SBIN
# created_at: 2026-06-09 09:15:XX
# entry_price: [actual entry]
# exit_price: [actual exit]
# outcome_status: CLOSED
# realized_pnl: [small value, ~0% ±1%]
# test_trade: true
```

---

# VERIFICATION RESULTS

## ✅ LOCAL VERIFICATION (Source Code)
- [x] Git commit: 498ec24d ✅
- [x] Configuration file: Correct ✅
- [x] Code changes compiled: ✅
- [x] JAR built: 90MB ✅

## ☐ REMOTE VERIFICATION (Production Server - PENDING)
- [ ] Docker image running
- [ ] Runtime config matches
- [ ] Startup logs clean
- [ ] Health check passes
- [ ] Cluster test passed
- [ ] Manual exit test passed

## ☐ MARKET OPEN VERIFICATION (9:15 UTC)
- [ ] Test trade generated
- [ ] Signal lifecycle complete
- [ ] Position properly tracked
- [ ] Exit executed
- [ ] Outcome recorded

---

# EXECUTION TIMELINE

| Time (UTC) | Action | Owner |
|-----------|--------|-------|
| 08:00 | Deployment ready (this report) | SYSTEM |
| 08:15 | Deploy JAR to production | DEVOPS |
| 08:25 | Verify startup logs | DEVOPS |
| 08:30 | Run verification checklist | QA |
| 08:45 | All systems verified ready | LEAD |
| 09:15 | Market open, generate TEST_TRADE_MARKETOPEN | SYSTEM |
| 09:20 | Verify test trade completed | QA |
| 09:25-14:45 | Normal trading resumes | SYSTEM |

---

# SIGN-OFF

**Pre-Market Readiness**: [PENDING SERVER VERIFICATION]

```
Local Status:     ✅ PASS (source verified)
Deployment:       ✅ READY (JAR ready, config valid)
Server Status:    ☐ PENDING (awaiting verification script)
Market Readiness: ☐ PENDING (awaiting test trade at 09:15)
```

**Next Steps**:
1. Run verification script on production server
2. Confirm all 10 checks pass
3. Execute TEST_TRADE_MARKETOPEN at 09:15
4. Verify test trade completes successfully
5. Resume normal trading

---

**Report Generated**: Pre-Market Verification Phase  
**Status**: READY FOR DEPLOYMENT  
**Risk Level**: LOW (all local checks passed, remote verification pending)

