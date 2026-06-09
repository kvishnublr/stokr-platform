# ✅ DEPLOYMENT READY CHECKLIST
## Complete Pre-Market Verification & Market Open Test Plan

**Status**: READY FOR PRODUCTION DEPLOYMENT  
**Deployment Date**: 2026-06-09  
**Market Open**: 09:15 UTC  
**Test Time**: 09:15 UTC (TEST_TRADE_MARKETOPEN)

---

# SUMMARY OF CHANGES

## 3 Commits Deployed

| Commit | Type | Changes | Status |
|--------|------|---------|--------|
| `498ec24d` | OPTIMIZE | Remove redundant filter | ✅ MERGED |
| `898f418f` | FIX | 2 critical bugs + config | ✅ MERGED |
| `f5d8d27f` | FEATURE | 4 safety improvements | ✅ MERGED |

## 2 Critical Bugs Found & Fixed

| Bug | Severity | Status |
|-----|----------|--------|
| Cluster threshold logic (>= instead of >) | 🔴 CRITICAL | ✅ FIXED |
| Signal lookup wrong time direction (BEFORE vs SINCE) | 🔴 CRITICAL | ✅ FIXED |

## 4 Features Implemented

| Feature | Status | Expected Impact |
|---------|--------|-----------------|
| OrderCooldownRule (30s) | ✅ ACTIVE | Prevent re-entry (-7.37%) |
| Manual Exit Outcome Update | ✅ ACTIVE | Fix signal lifecycle |
| ClusterDetectionRule | ✅ ACTIVE | Prevent batch losses (-7.82%) |
| OMS Repository Query | ✅ ACTIVE | Enable cluster detection |

---

# DEPLOYMENT VERIFICATION TIMELINE

## 08:00 UTC - Reports Ready ✅

```
✅ Local verification complete
✅ Git commit verified: 498ec24d
✅ Configuration verified: correct
✅ JAR built: 90MB
✅ All changes pushed to github
✅ Documentation complete
```

## 08:15 UTC - Deploy to Server

**Action**: Deploy new Docker image to production server (173.249.55.84)

```bash
# On production server:
cd /path/to/stokr-platform

# Build image
docker build -t stokr-api:498ec24d .

# Stop current
docker stop stokr-api

# Start new
docker-compose -f docker-compose.yml up -d

# Wait for startup
sleep 30

# Verify health
curl http://localhost:8080/actuator/health
```

## 08:30 UTC - Run Verification Script

**Action**: Run PRE_MARKET_READINESS verification on server

```bash
# Copy script to server
scp REMOTE_VERIFICATION_SCRIPT.sh root@173.249.55.84:/tmp/

# Run on server
ssh root@173.249.55.84 'bash /tmp/REMOTE_VERIFICATION_SCRIPT.sh'
```

**Expected Output**: ✅ ALL CHECKS PASSED

```
================================
VERIFICATION SUMMARY
================================
Passed: 8-10
Failed: 0
✅ ALL CHECKS PASSED
```

## 08:45 UTC - Final Pre-Market Check

**Action**: Manual verification of 10 critical checks

| # | Check | Expected | Status |
|---|-------|----------|--------|
| 1 | Git Commit | 498ec24d | ☐ |
| 2 | Docker Container | RUNNING | ☐ |
| 3 | Health Check | UP | ☐ |
| 4 | Cooldown Config | 30000 ms | ☐ |
| 5 | Cluster Enabled | true | ☐ |
| 6 | Max Entries | 2 | ☐ |
| 7 | Startup Logs | Clean | ☐ |
| 8 | Broker Connection | Active | ☐ |
| 9 | Database | Connected | ☐ |
| 10 | Cluster Test | Pass | ☐ |

**Gate**: If all 10 pass, proceed to trading. If any fail, fix before proceeding.

## 09:15 UTC - Execute TEST_TRADE_MARKETOPEN

**Action**: Generate test signal on SBIN (cheapest NIFTY50 stock)

**Test Specs**:
- Symbol: SBIN
- Strategy: INDEX_HUNT
- Test Mode: YES (test_trade=true)
- Quantity: 1
- Expected: Entry → Exit → CLOSED within 5 minutes

**Success Criteria**:
- ✅ Signal generated at 09:15
- ✅ Entry executed within 5 seconds
- ✅ Exit triggered automatically
- ✅ Outcome recorded as CLOSED
- ✅ No errors in logs
- ✅ No CLUSTER_DETECTION rejection

**Verification**:
```sql
SELECT 
    symbol, created_at, entry_price, exit_price,
    realized_pnl, outcome_status, test_trade
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
LIMIT 1;
```

Expected: ✅ CLOSED with small P&L

## 09:25 UTC - Resume Normal Trading

**Action**: If test passed, resume normal trading for rest of day

**Monitor**:
- Watch for cluster detections in logs
- Verify cooldown enforcement
- Verify manual exit handling
- Monitor for any errors

---

# CRITICAL CONFIGURATION VALUES

**Verify these are ACTIVE on production server:**

```
order-cooldown-ms:                    30000  (was 0)
cluster-detection-enabled:            true
cluster-max-entries:                  2
cluster-detection-window-minutes:     2
quality-floor (INDEX_HUNT):           75     (was 68)
GRASIM disabled:                      true   (via strategy config)
```

**How to verify**:
```bash
curl http://localhost:8080/actuator/configprops | grep -A 20 '"stokr"'
```

---

# ROLLBACK PLAN

If critical production issue discovered:

**Immediate Action** (< 5 minutes):
```bash
# Stop problematic container
docker stop stokr-api

# Revert to previous commit
git checkout 01baad21

# Rebuild
docker build -t stokr-api:backup .

# Restart
docker-compose up -d

# Verify
curl http://localhost:8080/actuator/health
```

**Full Rollback** (< 15 minutes total):
```bash
# If partial rollback didn't work:
docker-compose down
docker system prune -f
docker-compose -f docker-compose.yml up -d --build
```

**Data Loss**: NONE (no schema changes)  
**Trading Interruption**: 5-15 minutes maximum

---

# EXPECTED RESULTS TODAY

### Morning (09:15 - 14:45 UTC)

**Good Indicators**:
- TEST_TRADE_MARKETOPEN completes successfully
- No cluster detections for normal trading volume
- Cooldown rule working (blocks rapid re-entry)
- Manual exits properly detected
- No ERROR logs

**Warnings**:
- If cluster detection fires on legitimate trades (adjust threshold)
- If any signals remain RUNNING after manual exit
- If duplicate exit orders generated
- If quality score doesn't match configuration

### End of Day Summary

By 14:45 UTC, should have:
- ✅ Successful TEST_TRADE_MARKETOPEN
- ✅ 5-20 regular trades executed
- ✅ All trades properly tracked
- ✅ Exits executed automatically
- ✅ No production errors
- ✅ Configuration working as deployed

---

# MONITORING COMMANDS

### Real-Time Log Monitoring

```bash
# Watch all messages
docker logs -f stokr-api

# Watch errors only
docker logs -f stokr-api | grep ERROR

# Watch cluster detections
docker logs -f stokr-api | grep cluster.detection.triggered

# Watch manual exits
docker logs -f stokr-api | grep signal.outcome.auto_updated

# Watch cooldown enforcement
docker logs -f stokr-api | grep -E "cooldown|OrderCooldownRule"
```

### Database Monitoring

```bash
# Check active running signals
SELECT symbol, outcome_status, created_at 
FROM strategy_signals 
WHERE outcome_status IN ('RUNNING', 'PENDING')
ORDER BY created_at DESC;

# Check closed signals from today
SELECT symbol, realized_pnl, outcome_status 
FROM strategy_signals 
WHERE DATE(created_at) = '2026-06-09'
AND outcome_status = 'CLOSED'
ORDER BY created_at DESC;

# Check for any manual exits detected
SELECT symbol, outcome_comment 
FROM strategy_signals 
WHERE outcome_comment LIKE '%MANUAL_BROKER_EXIT%'
AND DATE(created_at) = '2026-06-09';

# Check cluster detection activity
SELECT symbol, created_at 
FROM oms_orders 
WHERE DATE(created_at) = '2026-06-09'
AND state = 'REJECTED'
LIMIT 100;
```

---

# DOCUMENTATION PROVIDED

All documents are in `/stokr-platform/`:

| Document | Purpose |
|----------|---------|
| **PRE_MARKET_READINESS_REPORT.md** | Complete pre-market checklist |
| **REMOTE_VERIFICATION_SCRIPT.sh** | Automated server verification |
| **TEST_TRADE_MARKETOPEN_INSTRUCTIONS.md** | Test execution guide |
| **FINAL_VERIFICATION_REPORT.md** | Deep analysis of bugs & fixes |
| **IMPLEMENTATION_REPORT_COMPLETE.md** | Implementation details |
| **DEPLOYMENT_READY_CHECKLIST.md** | This document |

---

# BEFORE YOU DEPLOY

**Checklist**:
- [ ] Read PRE_MARKET_READINESS_REPORT.md
- [ ] Copy REMOTE_VERIFICATION_SCRIPT.sh to server
- [ ] Confirm 498ec24d is latest git commit
- [ ] Confirm JAR is 90MB
- [ ] Confirm all docs copied
- [ ] Confirm TEST_TRADE_MARKETOPEN ready at 09:15
- [ ] Confirm team aware of test time
- [ ] Confirm rollback plan understood

---

# DEPLOYMENT SIGN-OFF

**All Checks Complete**: ✅
- [x] Code verified
- [x] Bugs fixed
- [x] Compiled without errors
- [x] JAR built successfully
- [x] Changes pushed to github
- [x] Documentation complete
- [x] Verification scripts prepared
- [x] Test plan ready

**Status**: 🟢 **READY FOR PRODUCTION DEPLOYMENT**

---

# QUICK REFERENCE COMMANDS

**Deploy**:
```bash
docker build -t stokr-api:498ec24d . && docker stop stokr-api && docker-compose up -d
```

**Verify**:
```bash
bash /tmp/REMOTE_VERIFICATION_SCRIPT.sh
```

**Monitor**:
```bash
docker logs -f stokr-api | grep -E "cluster|cooldown|outcome"
```

**Check Test Trade**:
```bash
SELECT * FROM strategy_signals WHERE symbol='SBIN' AND test_trade=true AND DATE(created_at)='2026-06-09' LIMIT 1;
```

**Rollback**:
```bash
git checkout 01baad21 && docker build -t stokr-api:backup . && docker-compose up -d
```

---

**Deployment Status**: ✅ READY  
**Go/No-Go Decision Point**: 08:45 UTC (10 checks)  
**Test Execution**: 09:15 UTC (TEST_TRADE_MARKETOPEN)  
**Expected Ready for Live Trading**: 09:25 UTC

