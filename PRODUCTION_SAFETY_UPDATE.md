# PRODUCTION SAFETY UPDATE
## P0 Implementation Plan with Mandatory Safety Controls

**Version:** 2.0  
**Changes:** Added stale price validation, dry-run mode, kill-switch, schema simplification  

---

## PART 1: SCHEMA SIMPLIFICATION REPORT

### Challenge: New Database Tables

**Question:** Do we really need `position_exit_audit` table?

---

### Analysis: Table By Table

#### Proposed Table: position_exit_audit

**Original Purpose:** Record all exit decisions for audit trail

**Alternatives Considered:**

**Option 1: Use JSON column in oms_orders table (PREFERRED)**
```
Pros:
  - No new table
  - Exit tied to order (natural relationship)
  - Minimal migration
  - Reuses existing OMS structure
  
Cons:
  - Query complexity for audit reports (need JSON extraction)
  
Risk: LOW (JSON queries are standard)
```

**Option 2: Use existing event infrastructure**
```
Pros:
  - Reuses Spring ApplicationEvent listeners
  - Works with existing event publishing
  
Cons:
  - Events are ephemeral (not persisted by default)
  - Need to add event persistence separately
  
Risk: MEDIUM (requires event table design)
```

**Option 3: Create position_exit_audit table (Original Plan)**
```
Pros:
  - Dedicated table for compliance queries
  - Easy reporting
  
Cons:
  - New schema migration
  - Duplicate data (info also in oms_orders)
  - Extra database table
  
Risk: MEDIUM (migrations can fail)
```

---

### DECISION: OPTION 1 (JSON in oms_orders)

**Rationale:**
- Minimizes schema changes
- Keeps exit information with order
- Audit trail naturally tied to order lifecycle
- Can query JSON fields with PostgreSQL
- Easier schema evolution

**Implementation:**
```sql
-- Minimal change to existing oms_orders table
ALTER TABLE oms_orders ADD COLUMN (
    exit_metadata JSON  -- Contains: reason, trigger_time, market_data_age, etc.
);
```

**Example exit_metadata content:**
```json
{
    "exit_reason": "TARGET_HIT",
    "trigger_timestamp": "2026-06-09T10:15:23Z",
    "market_data_timestamp": "2026-06-09T10:15:10Z",
    "market_data_age_seconds": 13,
    "entry_price": 1000.50,
    "exit_price": 1008.50,
    "triggered_by_service": "POSITION_MONITORING_SERVICE"
}
```

**Query for audit report:**
```sql
SELECT 
    id, user_id, symbol, created_at,
    exit_metadata->>'exit_reason' as exit_reason,
    exit_metadata->>'exit_price' as exit_price,
    exit_metadata->>'trigger_timestamp' as triggered_at
FROM oms_orders
WHERE exit_metadata IS NOT NULL
ORDER BY created_at DESC;
```

---

### Schema Changes Required (Simplified)

**MINIMAL changes:**

```sql
-- Migration 1: Add exit metadata column to oms_orders
ALTER TABLE oms_orders ADD COLUMN (
    exit_metadata JSON,
    exit_order_reason VARCHAR(50)  -- POSITION_MONITORING_SERVICE, TERMINAL_FLATTEN, etc.
);

-- Index for queries
CREATE INDEX idx_exit_metadata ON oms_orders 
USING GIN (exit_metadata);
```

**Total schema changes:**
- 1 JSON column
- 1 VARCHAR column  
- 1 GIN index
- 0 new tables
- ~10 lines SQL

---

## PART 2: STALE PRICE VALIDATION (Now P0)

### Requirement: Stale Market Data Protection

**Rule:** 
```
IF marketData.age > 15 seconds
THEN skip evaluation
ELSE proceed
```

**Why P0?**
- Stale prices cause false exit signals
- False exits are unacceptable in production
- Must validate before ANY decision

---

### Component: StalePriceValidator

**Location:** stokr-oms/service/StalePriceValidator.java

**Responsibility:**
```java
public class StalePriceValidator {
    public PriceValidationResult validate(
        String symbol,
        BigDecimal price,
        Instant priceTimestamp,
        int maxAgeSeconds  // default: 15
    ) {
        // Return: VALID / STALE / MISSING
        // Log details
        // Publish event
    }
}
```

**Input:**
- Symbol
- Price value
- Price timestamp (when candle opened)
- Max acceptable age (15 seconds)

**Output (PriceValidationResult):**
```java
public record PriceValidationResult(
    boolean valid,
    long ageSeconds,
    String reason,  // "VALID", "STALE", "MISSING"
    Instant dataTimestamp,
    Instant evaluationTimestamp
) {}
```

**Behavior:**

```
Calculate age = NOW - priceTimestamp

If age < 15 seconds:
    ✓ VALID
    Proceed with evaluation

If age >= 15 seconds:
    ✗ STALE
    Skip evaluation
    Log warning: "Stale price for SBIN: age=22s, skipping"
    
If price null:
    ✗ MISSING
    Skip evaluation
    Log debug: "No price data for TCS"
```

---

### Validation Flow

```
PositionMonitoringService.processUserPositions()
    ↓
Load positions
    ↓
Get market prices + timestamps
    ↓
For each position:
    ├─ Call StalePriceValidator.validate()
    │
    ├─ IF VALID:
    │  └─ Load entry order
    │  └─ Evaluate target/stop
    │  └─ Create ExitDecision
    │
    ├─ IF STALE:
    │  └─ Log: "SKIP: Stale price"
    │  └─ Publish event: PriceValidationFailedEvent
    │  └─ Continue to next position
    │
    └─ IF MISSING:
       └─ Log: "SKIP: No price data"
       └─ Continue to next position
```

---

### Audit Events

**Event 1: PriceValidationFailedEvent**
```json
{
    "timestamp": "2026-06-09T10:15:23Z",
    "symbol": "SBIN",
    "reason": "STALE",
    "data_age_seconds": 22,
    "max_age_seconds": 15,
    "data_timestamp": "2026-06-09T10:15:01Z",
    "evaluation_timestamp": "2026-06-09T10:15:23Z"
}
```

**Logging:**
```
DEBUG: Price validation for SBIN: age=13s, VALID
WARN:  Price validation for INFY: age=22s, STALE (max 15s)
DEBUG: Price validation for TCS: MISSING (no candle data)
```

---

## PART 3: DRY RUN MODE (Safe Observation)

### Two-Flag Control System

**Flag 1: stokr.position-monitor-enabled**
```
Controls: Is monitoring running?
Values: true / false
```

**Flag 2: stokr.position-monitor-exit-orders-enabled**
```
Controls: Should exit orders actually be created?
Values: true / false
```

---

### Operating Modes

```
┌────────────────────────────────────────────────────────┐
│ Flag 1: Monitor  │ Flag 2: Orders  │ Behavior         │
├──────────────────┼─────────────────┼──────────────────┤
│ FALSE            │ FALSE           │ ▬ IDLE           │
│ FALSE            │ TRUE            │ ▬ IDLE (order OFF)│
│ TRUE             │ FALSE           │ 👁 DRY RUN       │
│ TRUE             │ TRUE            │ ✓ PRODUCTION     │
└────────────────────────────────────────────────────────┘
```

---

### Mode 1: IDLE (Both Flags OFF)

```
Scheduler: Does not run
PositionMonitoringService: Not called
ExitOrderCreationService: Not called
Effect: Zero side effects
```

**Configuration:**
```properties
stokr.position-monitor-enabled=false
stokr.position-monitor-exit-orders-enabled=false
```

**Use case:** Before deployment, during testing, emergency shutdown

---

### Mode 2: DRY RUN (Monitor ON, Orders OFF)

```
Scheduler: Runs every 30 seconds
PositionMonitoringService: Evaluates positions
ExitOrderCreationService: CALLED BUT BLOCKED
Effect: Observes without creating orders
```

**Flow:**
```
PositionMonitoringService.processUserPositions()
    ↓
Load positions ✓
    ↓
Validate price ✓
    ↓
Evaluate target/stop ✓
    ↓
Create ExitDecision ✓
    ↓
Publish ExitEvent ✓
    ↓
Call ExitOrderCreationService ✓
    ↓
ExitOrderCreationService.createExitOrder()
    ├─ Check flag: exit-orders-enabled?
    │  
    ├─ IF FALSE:
    │  └─ Log: "DRY_RUN: Would create exit order"
    │  └─ Log decision details
    │  └─ Return special marker: "NOT_CREATED"
    │  └─ Do NOT call OrderPlacementService
    │
    └─ IF TRUE:
       └─ Create order via OrderPlacementService ✓
```

**Logging (DRY RUN mode):**
```
INFO: DRY_RUN: Would exit SBIN - Target hit (1008.50 >= 1008.00) - qty=100
INFO: DRY_RUN: Would exit INFY - Stop loss hit (455.20 <= 456.00) - qty=50
DEBUG: Dry run prevents order creation (exit-orders-enabled=false)
```

**Audit Event (DRY RUN):**
```json
{
    "timestamp": "2026-06-09T10:15:23Z",
    "event_type": "DRY_RUN_EXIT_DETECTED",
    "user_id": "user123",
    "symbol": "SBIN",
    "exit_reason": "TARGET_HIT",
    "entry_price": 1000.50,
    "exit_price": 1008.50,
    "quantity": 100,
    "order_created": false,
    "reason_not_created": "DRY_RUN_MODE"
}
```

**Configuration:**
```properties
stokr.position-monitor-enabled=true
stokr.position-monitor-exit-orders-enabled=false
```

**Use case:** Pre-production observation, validate logic without risk

**Duration:** 2-3 trading sessions (observe 50+ positions)

**Success criteria:**
- Targets detected correctly
- Stop losses detected correctly
- Duplicate prevention works (no duplicate logs)
- Audit events generated for every decision

---

### Mode 3: PRODUCTION (Both Flags ON)

```
Scheduler: Runs every 30 seconds
PositionMonitoringService: Evaluates positions
ExitOrderCreationService: Creates actual orders
Effect: Full automatic exits
```

**Flow:**
```
PositionMonitoringService.processUserPositions()
    ↓
Load positions ✓
    ↓
Validate price ✓
    ↓
Evaluate target/stop ✓
    ↓
Create ExitDecision ✓
    ↓
Publish ExitEvent ✓
    ↓
Call ExitOrderCreationService ✓
    ↓
ExitOrderCreationService.createExitOrder()
    ├─ Check flag: exit-orders-enabled?
    │
    └─ IF TRUE:
       ├─ Check duplicate ✓
       ├─ Create MARKET order ✓
       ├─ Call OrderPlacementService ✓
       ├─ Log: "Exit order created"
       └─ Return OmsOrder
```

**Logging (PRODUCTION mode):**
```
INFO: Exit order created: SBIN/order-uuid - Target hit at 1008.50
INFO: Exit order created: INFY/order-uuid - Stop loss hit at 455.20
```

**Audit Event (PRODUCTION):**
```json
{
    "timestamp": "2026-06-09T10:15:23Z",
    "event_type": "EXIT_ORDER_CREATED",
    "user_id": "user123",
    "symbol": "SBIN",
    "exit_reason": "TARGET_HIT",
    "order_id": "order-uuid",
    "order_created": true
}
```

**Configuration:**
```properties
stokr.position-monitor-enabled=true
stokr.position-monitor-exit-orders-enabled=true
```

**Use case:** Live trading, real automatic exits

---

## PART 4: HARD KILL SWITCH

### Emergency Shutdown Architecture

**Primary Switch:**
```properties
stokr.position-monitor-enabled=false
```

**Effect:**
```
PositionMonitoringScheduler.monitorOpenPositions()
    ↓
@Scheduled method called
    ↓
if (!monitoringEnabled) {
    log.debug("Position monitoring disabled");
    return;  // Exit immediately
}
    ↓
Zero processing
Zero orders
Zero side effects
```

**Rollback Time:** < 30 seconds
```
1. Update property file (1 second)
2. Reload config (5 seconds)
3. Verify in logs (10 seconds)
4. Total: < 30 seconds
```

**Implementation:**
```java
@Component
@Scheduled(fixedDelay = 30000)
public class PositionMonitoringScheduler {
    
    @Value("${stokr.position-monitor-enabled:true}")
    private boolean monitoringEnabled;
    
    public void monitorOpenPositions() {
        // KILL SWITCH - First line of method
        if (!monitoringEnabled) {
            log.debug("Position monitoring disabled via configuration");
            return;  // STOP IMMEDIATELY
        }
        
        // ... rest of monitoring logic
    }
}
```

**Verification:**
```bash
# Check logs show monitoring disabled
tail -f /var/log/stokr/api.log | grep "Position monitoring disabled"

# Verify no new orders created
psql -c "SELECT COUNT(*) FROM oms_orders 
         WHERE created_at > NOW() - INTERVAL 1 MINUTE 
         AND exit_metadata IS NOT NULL;"
         
# Expected: 0 (no new exit orders)
```

---

### Secondary Switch (Safety Layer)

If primary switch fails for any reason:

```java
@Component
public class ExitOrderCreationService {
    
    @Value("${stokr.position-monitor-exit-orders-enabled:true}")
    private boolean exitOrdersEnabled;
    
    // Double-check before creating orders
    public OmsOrder createExitOrder(...) {
        if (!exitOrdersEnabled) {
            throw new DisabledException("Exit orders disabled");
        }
        
        // ... create order
    }
}
```

**Prevents:** Even if scheduler runs, orders cannot be created

---

## PART 5: REVISED P0 SCOPE

### Original P0 (17 components)
```
✓ ExitReason
✓ ExitDecision
✓ ExitEvent
✓ TargetHitEvaluator
✓ StopLossEvaluator
✓ DuplicateExitChecker
✓ PositionMonitoringService
✓ PositionMonitoringScheduler
✓ Audit (via JSON in oms_orders)
✓ Tests
```

### New Additions (P0 Safety)
```
+ StalePriceValidator (NEW)
+ DryRunMode support (2 flags)
+ HardKillSwitch (built into scheduler)
+ Schema simplification (JSON instead of table)
```

### Removed
```
- position_exit_audit table (use JSON instead)
```

---

## PART 6: UPDATED CLASS LIST

### Total Components: 18 (was 17)

**Domain Models (3):**
1. ExitReason.java
2. ExitDecision.java
3. ExitEvent.java

**Configuration (1):**
4. PositionMonitoringConfig.java

**Validators (2) - NEW:**
5. **StalePriceValidator.java** (NEW - validates data age)
6. PriceValidationResult.java (record for validation result)

**Evaluation Services (3):**
7. TargetHitEvaluator.java
8. StopLossEvaluator.java
9. ExitEvaluationService.java (combines above two)

**OMS Integration (2):**
10. DuplicateExitChecker.java
11. ExitOrderCreationService.java (now checks exit-orders-enabled flag)

**Core Monitoring (2):**
12. PositionMonitoringService.java (now calls StalePriceValidator)
13. PositionMonitoringScheduler.java (now checks monitor-enabled flag)

**Events & Audit (2):**
14. PositionExitEventListener.java
15. PriceValidationFailedEvent.java (NEW - for stale price)

**Test Suites (5):**
16. TargetHitEvaluatorTest.java
17. StopLossEvaluatorTest.java
18. DuplicateExitCheckerTest.java
19. ExitOrderCreationServiceTest.java
20. PositionMonitoringServiceTest.java
21. **StalePriceValidatorTest.java** (NEW)

**Total: 21 components**

---

## PART 7: UPDATED DATABASE CHANGES

### Simplified Schema

**Change 1: Add JSON column to oms_orders**
```sql
ALTER TABLE oms_orders ADD COLUMN (
    exit_metadata JSON,        -- Exit decision details
    exit_order_reason VARCHAR(50)  -- Why order was created
);

CREATE INDEX idx_exit_metadata ON oms_orders 
USING GIN (exit_metadata);
```

**Total SQL:** ~15 lines  
**Risk:** LOW (additive only, no data loss)  
**Rollback:** Can drop column if needed

### Migration File
```
Location: stokr-oms/src/main/resources/db/migration/
File: V1_001__AddExitMetadataToOmsOrders.sql
```

---

## PART 8: UPDATED CODING ORDER

### Complete P0 Implementation (22 steps)

```
STEP 1: Domain Models (30 min)
  1.1 ExitReason.java
  1.2 ExitDecision.java
  1.3 ExitEvent.java

STEP 2: Database (15 min)
  2.1 SQL Migration (oms_orders.exit_metadata)

STEP 3: Configuration (15 min)
  3.1 application.properties (two flags)
  3.2 PositionMonitoringConfig.java

STEP 4: Stale Price Validator (60 min) ← NEW
  4.1 PriceValidationResult.java
  4.2 StalePriceValidator.java (validates data age)

STEP 5: Evaluation Logic (60 min)
  5.1 TargetHitEvaluator.java
  5.2 StopLossEvaluator.java

STEP 6: OMS Integration (75 min)
  6.1 DuplicateExitChecker.java
  6.2 ExitOrderCreationService.java (check exit-orders flag)

STEP 7: Core Monitoring (90 min)
  7.1 PositionMonitoringService.java (call StalePriceValidator)
  7.2 PositionMonitoringScheduler.java (check monitor flag + kill switch)

STEP 8: Events & Audit (40 min)
  8.1 PositionExitEventListener.java
  8.2 PriceValidationFailedEvent.java (NEW)

STEP 9: Repository Methods (10 min)
  9.1 OmsOrderRepository.add methods
  9.2 PortfolioPositionRepository.add methods

STEP 10: Tests (330 min)
  10.1 TargetHitEvaluatorTest.java
  10.2 StopLossEvaluatorTest.java
  10.3 StalePriceValidatorTest.java (NEW)
  10.4 DuplicateExitCheckerTest.java
  10.5 ExitOrderCreationServiceTest.java
  10.6 PositionMonitoringServiceTest.java
  10.7 DryRunModeTest.java (NEW)
  10.8 KillSwitchTest.java (NEW)

STEP 11: Build & Verify (35 min)
  11.1 Compile
  11.2 Tests

TOTAL TIME: 1260 minutes ≈ 21 hours development
TIMELINE: 5-6 days (1 developer, full-time)
```

---

## PART 9: DEPLOYMENT VALIDATION CHECKLIST

### Pre-Deployment

```
Code & Tests:
[ ] All 21 components compile cleanly
[ ] All test suites pass (8 test classes)
[ ] No compiler warnings
[ ] Code review approved
[ ] Tests cover 100% of core logic

Database:
[ ] Migration script reviewed
[ ] Tested on staging database
[ ] Rollback procedure documented
[ ] No data loss in forward migration

Configuration:
[ ] Both feature flags present in application.properties
[ ] Defaults are SAFE (monitor ON, orders OFF)
[ ] Feature flags can be updated without restart
[ ] Kill switch tested

Logging:
[ ] StalePriceValidator logs correctly
[ ] DryRunMode logs "Would exit" messages
[ ] KillSwitch logs disable message
[ ] Audit events logged

Health Checks:
[ ] Health endpoint reports all systems OK
[ ] No errors on startup
[ ] Scheduler registered
[ ] Event listeners registered
```

---

### Stage 1 Deployment (Code Only)

```
Configuration:
[ ] stokr.position-monitor-enabled = false
[ ] stokr.position-monitor-exit-orders-enabled = false

Deployment:
[ ] Deploy code to production
[ ] Verify no startup errors
[ ] Verify health endpoint OK
[ ] Verify logs show "monitoring disabled"

Result: Zero side effects, safe baseline
```

---

### Stage 2 Deployment (Dry Run)

```
Configuration:
[ ] stokr.position-monitor-enabled = true
[ ] stokr.position-monitor-exit-orders-enabled = false

Deployment:
[ ] Enable feature flag
[ ] Monitor logs for 2-3 trading sessions
[ ] Verify target hits detected
[ ] Verify stop losses detected
[ ] Verify dry-run logs show "Would exit"
[ ] Verify duplicate detection works
[ ] Verify stale price detection works
[ ] Check audit events generated
[ ] Verify no actual orders created

Success Criteria:
[ ] 50+ positions evaluated without error
[ ] 100% accuracy on target detection
[ ] 100% accuracy on stop loss detection
[ ] No duplicate "would exit" logs for same position
[ ] Stale price skips logged appropriately

If any failure: Revert to Stage 1
```

---

### Stage 3 Deployment (Paper Trading)

```
Configuration:
[ ] stokr.position-monitor-enabled = true
[ ] stokr.position-monitor-exit-orders-enabled = true
[ ] ExecutionMode = PAPER (not LIVE)

Deployment:
[ ] Enable exit orders for paper trading account
[ ] Monitor for 1 trading session
[ ] Verify exit orders created in OMS
[ ] Verify orders routed to paper broker
[ ] Verify positions updated correctly
[ ] Verify audit trail complete

Success Criteria:
[ ] 10+ exit orders created
[ ] 0 errors in OMS
[ ] 0 duplicate orders
[ ] All audit metadata present

If any failure: Revert to Stage 2
```

---

### Stage 4 Deployment (Single User LIVE)

```
Configuration:
[ ] stokr.position-monitor-enabled = true
[ ] stokr.position-monitor-exit-orders-enabled = true
[ ] ExecutionMode = LIVE
[ ] Users enabled: [single-test-user-uuid]

Deployment:
[ ] Enable for 1 internal test user
[ ] Monitor for 1 trading session
[ ] Verify exits created correctly
[ ] Verify positions closed
[ ] Verify profit/loss calculated
[ ] Check if exits happened at reasonable prices

Success Criteria:
[ ] At least 1 exit order created
[ ] Position quantity = 0 after execution
[ ] P&L calculated correctly
[ ] Audit trail complete

If any failure: Revert to Stage 2
```

---

### Stage 5 Deployment (Gradual Rollout)

```
Day 1:
[ ] Enable for 1% of LIVE users
[ ] Monitor 4 hours
[ ] Check: no errors, expected exits

Day 2:
[ ] Enable for 5% of LIVE users
[ ] Monitor 8 hours
[ ] Check: system stable, exits reasonable

Day 3:
[ ] Enable for 25% of LIVE users
[ ] Monitor 24 hours
[ ] Check: performance, no regressions

Day 4:
[ ] Enable for 50% of LIVE users
[ ] Monitor 24 hours

Day 5:
[ ] Enable for 100% of LIVE users
[ ] Monitor continuously for 1 week
```

---

## PART 10: EMERGENCY PROCEDURES

### Scenario 1: Kill Switch

**Trigger:** Any critical issue detected

**Action (< 30 seconds):**
```bash
# Option A: Update property file
echo "stokr.position-monitor-enabled=false" >> application.properties
# Reload application config

# Option B: Set via environment
export STOKR_POSITION_MONITOR_ENABLED=false
# Restart application (2-5 minutes)

# Verify
tail -f logs | grep "Position monitoring disabled"
```

**Rollback:** Set flag back to true

---

### Scenario 2: Disable Orders Only

**Trigger:** Orders creating but something wrong with routing

**Action (< 2 minutes):**
```properties
stokr.position-monitor-exit-orders-enabled=false
```

**Result:** System continues monitoring but doesn't create orders (dry-run mode)

---

### Scenario 3: Full Rollback

**Trigger:** Unrecoverable issues

**Action:**
1. Disable monitoring: set flag to false
2. Verify no new orders created
3. Revert code to previous version
4. Rerun database migration rollback
5. Restart application

---

## PART 11: FINAL IMPLEMENTATION SUMMARY

### Scope: P0 Production-Safe Edition

**Components:** 21 classes
**Database:** 1 column addition (JSON)
**Code:** ~800 lines Java
**Tests:** ~450 lines
**Migrations:** ~15 lines SQL

**Safety Features:**
✅ Stale price validation (15-second threshold)
✅ Dry-run mode (observe without acting)
✅ Hard kill switch (disable in < 30 seconds)
✅ Duplicate prevention (prevents over-exits)
✅ Audit trail (JSON metadata in orders)
✅ Emergency rollback (fast reversion)

**Deployment:**
✅ 5-stage rollout (code → dry-run → paper → 1 user → gradual)
✅ Safe defaults (monitoring OFF, orders OFF)
✅ Observable behavior (comprehensive logging)
✅ Reversible changes (minimal schema impact)

**Production Ready:** YES

---

## CONCLUSION

This updated P0 plan is production-grade and safe:

1. **Stale price validation** prevents false exits
2. **Dry-run mode** allows safe observation
3. **Kill switches** enable instant rollback
4. **Simplified schema** minimizes risk
5. **Staged deployment** reduces blast radius
6. **Comprehensive audit** enables troubleshooting

**Ready to implement?**

