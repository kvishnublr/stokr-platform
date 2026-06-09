# FINAL IMPLEMENTATION PLAN - PHASE 1 P0
## Production-Safe Automatic Exit Framework

**Date:** June 9, 2026  
**Version:** 3.0 (Production Safety Edition)  
**Status:** READY FOR IMPLEMENTATION  
**Scope:** Minimum viable + mandatory safety controls  

---

## EXECUTIVE SUMMARY

### What We're Building

Automatically close open positions when:
- ✅ Target price is reached
- ✅ Stop-loss is reached

With mandatory safety controls:
- ✅ Stale price rejection (>15 seconds = skip)
- ✅ Dry-run mode (observe without acting)
- ✅ Hard kill switch (disable in <30 seconds)
- ✅ Duplicate prevention
- ✅ Complete audit trail

### What We're NOT Building

- ❌ Indicators (RSI, MACD, ATR, etc.)
- ❌ AI/ML optimization
- ❌ Confidence scoring
- ❌ Dynamic targets
- ❌ Advanced monitoring dashboards
- ❌ Parallelization
- ❌ Session controls (market hours)

---

## FINAL COMPONENT LIST

### 21 Total Components (Production P0)

**Domain Models (3):**
1. ExitReason enum (TARGET_HIT, STOP_LOSS_HIT)
2. ExitDecision (immutable decision)
3. ExitEvent (domain event)

**Configuration (1):**
4. PositionMonitoringConfig

**Validators (2):**
5. StalePriceValidator ← **NEW: CRITICAL**
6. PriceValidationResult

**Evaluation (3):**
7. TargetHitEvaluator
8. StopLossEvaluator
9. ExitEvaluationService (combines above)

**OMS Integration (2):**
10. DuplicateExitChecker
11. ExitOrderCreationService

**Core Monitoring (2):**
12. PositionMonitoringService
13. PositionMonitoringScheduler

**Events (2):**
14. PositionExitEventListener
15. PriceValidationFailedEvent ← **NEW**

**Tests (6):**
16. TargetHitEvaluatorTest
17. StopLossEvaluatorTest
18. StalePriceValidatorTest ← **NEW**
19. DuplicateExitCheckerTest
20. ExitOrderCreationServiceTest
21. PositionMonitoringServiceTest

**PLUS:**
- 2 new test classes: DryRunModeTest, KillSwitchTest
- 1 repository method addition set

---

## DATABASE CHANGES

**Minimal Schema Change:**

```sql
-- File: V1_001__AddExitMetadataToOmsOrders.sql

ALTER TABLE oms_orders ADD COLUMN (
    exit_metadata JSON,
    exit_order_reason VARCHAR(50)
);

CREATE INDEX idx_exit_metadata ON oms_orders 
USING GIN (exit_metadata);
```

**Total SQL:** 8 lines  
**Risk Level:** LOW (additive only)  
**Rollback:** Can drop columns

**Replaces:**
- ❌ position_exit_audit table (too many tables)
- ✅ Uses JSON in oms_orders (reuses existing table)

---

## FEATURE FLAGS

```properties
# File: application.properties

# Master kill switch - disables everything
stokr.position-monitor-enabled=true

# Separates observation from action
stokr.position-monitor-exit-orders-enabled=false

# Price freshness requirement
stokr.position-monitor-max-price-age-seconds=15
```

**Defaults:** Safe (monitoring ON, orders OFF = dry-run mode)

---

## EXACT IMPLEMENTATION SEQUENCE

### Phase 1: Foundation (1.5 hours)

```
STEP 1.1: Create ExitReason.java
  Location: stokr-oms/domain/
  Time: 10 min
  Complexity: TRIVIAL
  
STEP 1.2: Create ExitDecision.java
  Location: stokr-oms/domain/
  Time: 15 min
  Complexity: TRIVIAL
  
STEP 1.3: Create ExitEvent.java
  Location: stokr-common/events/
  Time: 15 min
  Complexity: TRIVIAL
```

### Phase 2: Database (0.25 hours)

```
STEP 2.1: Create SQL migration
  File: V1_001__AddExitMetadataToOmsOrders.sql
  Time: 15 min
  Complexity: TRIVIAL
  Contents: 8 SQL lines
```

### Phase 3: Configuration (0.5 hours)

```
STEP 3.1: Add feature flags
  File: application.properties
  Time: 10 min
  
STEP 3.2: Create PositionMonitoringConfig
  Location: stokr-oms/config/
  Time: 15 min
```

### Phase 4: Stale Price Validation (1.5 hours) ← **CRITICAL P0**

```
STEP 4.1: Create PriceValidationResult.java
  Location: stokr-oms/model/
  Time: 10 min
  Complexity: TRIVIAL
  
STEP 4.2: Create StalePriceValidator.java
  Location: stokr-oms/service/
  Time: 45 min
  Complexity: MEDIUM
  
  Responsibility:
  - Validate price age
  - Return VALID/STALE/MISSING
  - Log details
  - Publish PriceValidationFailedEvent
  
  Logic:
  ```
  IF age < 15 seconds:
    VALID
  ELSE:
    STALE
  ```
```

### Phase 5: Evaluation Logic (1.5 hours)

```
STEP 5.1: Create TargetHitEvaluator.java
  Location: stokr-oms/service/
  Time: 30 min
  Logic:
    - Check position is long/short
    - Compare currentPrice >= targetPrice (long)
    - Return ExitDecision if true
    
STEP 5.2: Create StopLossEvaluator.java
  Location: stokr-oms/service/
  Time: 30 min
  Logic:
    - Check position is long/short
    - Compare currentPrice <= stopPrice (long)
    - Return ExitDecision if true
```

### Phase 6: OMS Integration (1.5 hours)

```
STEP 6.1: Create DuplicateExitChecker.java
  Location: stokr-oms/service/
  Time: 30 min
  
  Responsibility:
  - Query: countByUserIdAndSymbolAndOrderReasonAndCreatedAfter()
  - Return boolean: hasRecentExitOrder()
  - 300-second window
  
STEP 6.2: Create ExitOrderCreationService.java
  Location: stokr-oms/service/
  Time: 45 min
  
  Responsibility:
  - Check exit-orders-enabled flag ← **DRY RUN CONTROL**
    IF false: Log "DRY_RUN" + return marker
    IF true: Create order via OrderPlacementService
  - Call DuplicateExitChecker
  - Determine SELL (long) or BUY (short)
  - Generate idempotency key
  - Call OrderPlacementService.place()
```

### Phase 7: Core Monitoring (2 hours)

```
STEP 7.1: Create PositionMonitoringService.java
  Location: stokr-oms/service/
  Time: 90 min
  
  Responsibility:
  - Load all OPEN positions for user
  - Load entry orders (has target/stop)
  - Get current market prices
  - For each position:
    ├─ Call StalePriceValidator ← **MANDATORY SAFETY**
    ├─ IF STALE: skip, log, continue
    ├─ IF VALID: evaluate target/stop
    ├─ IF decision: create exit order
    └─ Publish event
  
STEP 7.2: Create PositionMonitoringScheduler.java
  Location: stokr-oms/schedule/
  Time: 30 min
  
  Responsibility:
  - @Scheduled every 30 seconds
  - Check monitor-enabled flag ← **KILL SWITCH**
    IF false: return immediately
    IF true: proceed
  - Find all users with open positions
  - Call processUserPositions() for each
  - Handle errors gracefully
```

### Phase 8: Events & Audit (1 hour)

```
STEP 8.1: Create PositionExitEventListener.java
  Location: stokr-oms/event/
  Time: 20 min
  
  Responsibility:
  - Listen for ExitEvent
  - Build exit_metadata JSON
  - Save to oms_orders.exit_metadata
  - Log audit event
  
STEP 8.2: Create PriceValidationFailedEvent.java
  Location: stokr-common/events/
  Time: 10 min
  
  Responsibility:
  - Published when price stale
  - Log for troubleshooting
```

### Phase 9: Repository Methods (0.25 hours)

```
STEP 9.1: Modify OmsOrderRepository.java
  Add method:
  - countByUserIdAndSymbolAndOrderReasonAndCreatedAfter()
  Time: 5 min
  
STEP 9.2: Modify PortfolioPositionRepository.java
  Add method:
  - findDistinctUserIdByDeletedFalseAndQuantityNotZero()
  Time: 5 min
```

### Phase 10: Testing (5.5 hours)

```
STEP 10.1: Create TargetHitEvaluatorTest.java
  Test cases: 6
  Time: 45 min
  
STEP 10.2: Create StopLossEvaluatorTest.java
  Test cases: 5
  Time: 45 min
  
STEP 10.3: Create StalePriceValidatorTest.java ← **NEW**
  Test cases: 8
  Time: 60 min
  
  Must test:
  - Valid price (< 15s)
  - Stale price (> 15s)
  - Missing price
  - Boundary cases
  
STEP 10.4: Create DuplicateExitCheckerTest.java
  Test cases: 5
  Time: 45 min
  
STEP 10.5: Create ExitOrderCreationServiceTest.java
  Test cases: 8
  Time: 60 min
  
  Must test:
  - Order created when orders enabled
  - Dry-run when orders disabled
  - Correct side (SELL/BUY)
  - Idempotency key format
  
STEP 10.6: Create PositionMonitoringServiceTest.java
  Test cases: 10
  Time: 90 min
  
STEP 10.7: Create DryRunModeTest.java ← **NEW**
  Test cases: 5
  Time: 45 min
  
  Must test:
  - monitor-enabled=true, orders-enabled=false
  - Logs "DRY_RUN: Would exit"
  - No orders created
  - Events published
  
STEP 10.8: Create KillSwitchTest.java ← **NEW**
  Test cases: 4
  Time: 30 min
  
  Must test:
  - monitor-enabled=false
  - Scheduler returns immediately
  - No processing occurs
  - Can be toggled
```

### Phase 11: Build & Verify (0.75 hours)

```
STEP 11.1: Compile all code
  Command: ./gradlew clean build -x test
  Time: 20 min
  Must: 0 errors, 0 warnings
  
STEP 11.2: Run all unit tests
  Command: ./gradlew test
  Time: 15 min
  Must: All 8 test classes pass
  
STEP 11.3: Run integration tests
  Command: ./gradlew integrationTest
  Time: 20 min
  Must: All pass
```

---

## TIMELINE

### Single Developer (Full-Time)

```
Day 1 (8 hours):
  Phase 1: Foundation (1.5h)
  Phase 2: Database (0.25h)
  Phase 3: Configuration (0.5h)
  Phase 4: Stale Validation (1.5h)
  Phase 5: Evaluation (1.5h)
  Phase 6a: DuplicateChecker (0.5h)
  Breaks & buffer: 1.25h

Day 2 (8 hours):
  Phase 6b: ExitOrderCreation (0.75h)
  Phase 7: Core Monitoring (2h)
  Phase 8: Events (1h)
  Phase 9: Repository (0.25h)
  Phase 10a: First 4 test classes (3h)
  Breaks & buffer: 1h

Day 3 (8 hours):
  Phase 10b: Last 4 test classes (2.5h)
  Phase 11: Build & Verify (0.75h)
  Code review & corrections: 2h
  Documentation: 2.75h

Day 4:
  Final testing
  Staging deployment
  Ready for production rollout
```

**Total development:** 24 hours (3 days full-time)

---

## PRE-DEPLOYMENT CHECKLIST

### Code Quality
```
[ ] All 21 components compile cleanly
[ ] 0 compiler warnings
[ ] 0 test failures
[ ] Code review approved
[ ] Test coverage > 90%
```

### Safety
```
[ ] StalePriceValidator tested with multiple ages
[ ] DryRunMode tested (orders not created)
[ ] KillSwitch tested (scheduler stops)
[ ] Duplicate prevention tested
[ ] Idempotency keys tested
```

### Configuration
```
[ ] Feature flags present in application.properties
[ ] Defaults safe (monitor ON, orders OFF)
[ ] Flags can be changed without restart
[ ] Kill switch verified to work
```

### Database
```
[ ] Migration script tested on staging
[ ] Rollback procedure documented
[ ] JSON column created successfully
[ ] Index created for queries
```

### Deployment
```
[ ] Health check endpoint working
[ ] No startup errors
[ ] Logs show correct initialization
[ ] Feature flags are readable
```

---

## GO/NO-GO DECISION CRITERIA

### GO if:
✅ All unit tests pass  
✅ All integration tests pass  
✅ StalePriceValidator works correctly  
✅ DryRunMode works (orders not created)  
✅ KillSwitch works (scheduler stops)  
✅ Database migration successful  
✅ Feature flags controllable  
✅ Code review approved  

### NO-GO if:
❌ Any test fails  
❌ StalePriceValidator broken  
❌ Kill switch doesn't work  
❌ Database migration fails  
❌ Feature flags not working  
❌ Code review issues  

---

## DEPLOYMENT STRATEGY

### Stage 1: Code Deployment (No Features)
```
Configuration:
  stokr.position-monitor-enabled=false
  stokr.position-monitor-exit-orders-enabled=false

Risk: ZERO
Duration: Permanent (baseline)
Result: No automatic exits, system ready
```

### Stage 2: Dry-Run Mode (Observe)
```
Configuration:
  stokr.position-monitor-enabled=true
  stokr.position-monitor-exit-orders-enabled=false

Duration: 2-3 trading sessions
Observe: Target hits, stop losses, duplicates
Log: "DRY_RUN: Would exit..."
Result: Verify logic without risk
```

### Stage 3: Paper Trading
```
Configuration:
  stokr.position-monitor-enabled=true
  stokr.position-monitor-exit-orders-enabled=true
  ExecutionMode: PAPER

Duration: 1 trading session
Test: Orders created, routed correctly
Result: Verify OMS integration
```

### Stage 4: Single LIVE User
```
Configuration:
  Enable for 1 internal user
  
Duration: 1 trading session
Test: Real positions exit
Result: Verify production behavior
```

### Stage 5: Gradual Rollout
```
Day 1: 1% of users
Day 2: 5% of users
Day 3: 25% of users
Day 4: 50% of users
Day 5: 100% of users

Monitor continuously for anomalies
```

---

## EMERGENCY PROCEDURES

### Kill Switch (< 2 minutes)
```bash
# Disable monitoring immediately
stokr.position-monitor-enabled=false

Effect: Scheduler stops, zero processing
Verify: Logs show "monitoring disabled"
```

### Disable Orders Only (< 1 minute)
```bash
# Orders stop, monitoring continues (dry-run)
stokr.position-monitor-exit-orders-enabled=false

Effect: System observes but doesn't create orders
```

### Full Rollback (5-10 minutes)
```
1. Disable monitoring
2. Revert code
3. Rollback database migration
4. Restart application
```

---

## SUCCESS CRITERIA

### After Day 1 (Production)
- ✅ 50+ positions evaluated without error
- ✅ 5+ exits created
- ✅ 0 duplicate orders
- ✅ 0 stale price exits
- ✅ Complete audit trail

### After Week 1
- ✅ 500+ positions evaluated
- ✅ 50+ exits created
- ✅ Win rate > 50%
- ✅ No false exits
- ✅ No user complaints

### After Month 1
- ✅ System stable
- ✅ Exit accuracy > 95%
- ✅ Audit trail complete
- ✅ Ready for Phase 2

---

## PHASE 2 (After P0 Stable)

These are explicitly OUT of scope for P0:

- Market hours validation (SessionValidator)
- Performance metrics (Micrometer)
- Advanced dashboards
- Parallelization
- Indicators (RSI, MACD)
- AI optimization

Can be added after P0 is production-stable.

---

## CONCLUSION

### What We're Delivering

**Production-safe, minimum-viable automatic exit system:**

✅ **Automatically closes positions** when target/stop hit  
✅ **Rejects stale prices** (>15 seconds)  
✅ **Prevents duplicates** (one exit per position)  
✅ **Dry-run mode** (observe without acting)  
✅ **Kill switch** (disable in <30 seconds)  
✅ **Audit trail** (JSON metadata)  
✅ **Tested** (8 test suites, 30+ test methods)  
✅ **Staged rollout** (5 deployment phases)  

### Size & Complexity

- **Components:** 21 classes
- **Code:** ~800 lines Java
- **Tests:** ~450 lines
- **Database:** 1 column addition
- **Time:** 3 days (1 developer)

### Risk Level

**LOW** - Minimal schema changes, comprehensive tests, multiple kill switches

---

**READY FOR DEVELOPER ASSIGNMENT**

