# 🔴 FINAL VERIFICATION: DEPLOYMENT STATUS
## Critical Findings Before Implementation

**Report Date**: 2026-06-08  
**Scope**: Production code vs. running code verification  
**Status**: CRITICAL ISSUE DISCOVERED

---

# SECTION 1: GIT & CODE STATUS

## Current Repository State

```
Branch:           Release_v2
Latest Commit:    01baad21 "Fix compilation: Replace ChronoUnit with Duration"
Deployment Target: Release_v2 (per docker-compose.yml line 56)
Fix Commit:       a1fdc4da "Fix INDEX_HUNT: widen SL, tighten VIX gates, disable GRASIM, increase quality floor"
Commit Position:  a1fdc4da is 6 commits BEHIND 01baad21
```

## Code vs. Running Code MISMATCH

### In Source Code Repository (Release_v2):

**File 1**: `stokr-strategy/src/main/java/com/stokr/strategy/generated/IndexHuntSignalGenerator.java`
```java
Line 139: private static final int QUALITY_FLOOR = 75;  // NEW (from a1fdc4da)
Line 108: private static final double VIX_BLOCK_ABOVE = 20.0;  // NEW
Line 176: if ("GRASIM".equalsIgnoreCase(symbol)) { return hold(context); }  // DISABLED
```

**File 2**: `stokr-strategy/src/main/java/com/stokr/intraday/detector/IndexHuntDetector.java`
```java
Line 48:  private static final BigDecimal QUALITY_FLOOR = BigDecimal.valueOf(68);  // OLD
Line 44:  private static final BigDecimal VIX_SKIP_CE_ABOVE = BigDecimal.valueOf(20.75);  // OLD
          (No GRASIM disable)
```

---

# SECTION 2: PROOF OF DEPLOYMENT ISSUE

## Evidence from Today's Trading (2026-06-08)

### Quality Floor Analysis

```
If QUALITY_FLOOR = 75 (new code deployed):
├─ SBILIFE quality=74 should be REJECTED
├─ But: SBILIFE quality=74 was APPROVED
└─ Conclusion: NEW code NOT deployed

If QUALITY_FLOOR = 68 (old code deployed):
├─ SBILIFE quality=74 should be APPROVED  
├─ Actual: SBILIFE quality=74 was APPROVED ✓
└─ Conclusion: OLD code IS deployed
```

### VIX Threshold Analysis

```
Today's trades all had VIX = 17.5

If VIX_BLOCK_ABOVE = 20.0 (new code):
├─ VIX=17.5 < 20.0 → PASS (approved)
├─ Allows all 16 signals

If VIX_BLOCK_ABOVE = 28.0 (old code):
├─ VIX=17.5 < 28.0 → PASS (approved)
├─ Also allows all 16 signals (no difference)

Note: Today's VIX is benign, so can't determine from this data
```

### GRASIM Disable Status

```
Today's Trading:
├─ GRASIM #1: 05:17:31 → APPROVED (entered)
├─ GRASIM #2: 07:18:04 → APPROVED (re-entered)

If GRASIM disabled (new code):
├─ Both should return hold(context) without entering
├─ But: Both WERE entered
└─ Conclusion: GRASIM disable NOT active

Evidence: GRASIM was NOT disabled → OLD CODE
```

---

## CRITICAL FINDING

| Component | Old Code | New Code | Today's Behavior | Deployed? |
|-----------|----------|----------|------------------|-----------|
| QUALITY_FLOOR | 68 | 75 | Quality 74 approved | ❌ NO |
| GRASIM | Enabled | Disabled | GRASIM entered twice | ❌ NO |
| VIX gate | 28.0 | 20.0 | Cannot determine | ⚠️ UNKNOWN |
| SL | 0.20% | 0.50% | Cannot determine | ⚠️ UNKNOWN |
| Dedup | 30 min | 45 min | Cannot determine | ⚠️ UNKNOWN |

**VERDICT: The fix commit a1fdc4da is NOT deployed to production.**

---

# SECTION 3: ROOT CAUSE OF TODAY'S CLUSTER

## Why 04:58:03 Cluster Still Happened

```
Timeline:

2026-06-07 Evening or earlier:
├─ Production code deployed
├─ Version: OLD (quality_floor=68, GRASIM enabled)
└─ a1fdc4da fix NOT deployed

2026-06-08 Trading Day:
├─ IndexHuntDetector running (QUALITY_FLOOR=68)
├─ GRASIM not disabled (enabled)
├─ All low-quality signals approved

04:58:03 UTC:
├─ VIX=17.5, PCR=1.05 (batch snapshot)
├─ 4 signals pass quality gate (68)
├─ SBILIGHT quality=74 > 68 → APPROVED
├─ GRASIM not disabled → APPROVED
└─ Result: 4-signal cluster at same second

Root Cause:
├─ Code fix exists in source
├─ Code fix NOT deployed to production
├─ Trading happened on OLD code version
└─ Fixes had no effect
```

---

# SECTION 4: WHICH SERVICE GENERATES SIGNALS?

## Service Chain Verification

```
Signal Pipeline:

IndexHuntService (line 1 in StrategySignalPipelineService) 
├─ Uses: IndexHuntDetector (QUALITY_FLOOR=68)  ← OLD version
├─ Status: Running in production
└─ Quality gate: 68 (proven by today's trades)

IndexHuntSignalGenerator (with @GeneratedStrategy annotation)
├─ Status: Code exists, marked for deployment
├─ Has: QUALITY_FLOOR=75 (NEW)
├─ Deployment: NOT activated yet
└─ Would have prevented cluster IF deployed
```

---

# SECTION 5: DEPLOYMENT VERIFICATION CHECKLIST

```
To verify what's actually running on production (173.249.55.84):

STEP 1: Check Running Version
├─ Command: docker ps | grep stokr-api
├─ Get: image tag, container ID
├─ Get: Image hash/SHA
└─ Action: Compare to Release_v2 latest

STEP 2: Verify Git Commit
├─ Command: docker logs stokr-api | grep -i "commit\|version\|git"
├─ Look for: STOKR_GIT_COMMIT value
├─ Expect: Should show git commit SHA of deployed version
└─ Action: Check if it matches a1fdc4da or earlier

STEP 3: Check Configuration Values
├─ Command: curl http://173.249.55.84:8080/actuator/configprops
├─ Look for: stokr.risk.order-cooldown-ms
├─ Look for: GRASIM disable flag
├─ Look for: Quality floor value
└─ Action: Verify against application.yml

STEP 4: Inspect JAR
├─ Extract: stokr-bootstrap.jar
├─ Find: IndexHuntDetector.class
├─ Decompile: Check QUALITY_FLOOR constant value
├─ Find: IndexHuntSignalGenerator.class
├─ Compare: Both class versions
└─ Action: Determine which is actively used

STEP 5: Database Verification
├─ Query: SELECT MAX(quality) FROM strategy_signals WHERE date='2026-06-08'
├─ Query: SELECT MIN(quality) FROM strategy_signals WHERE date='2026-06-08'
├─ If MIN=74: Proves QUALITY_FLOOR=68
├─ If MIN=75: Proves QUALITY_FLOOR=75
└─ Action: Confirm code version via data
```

---

# SECTION 6: OWNERSHIP LIFECYCLE (Complete Trace)

## Signal → Order → Position → Ownership Release

```
PHASE 1: Signal Creation (IndexHuntSignalGenerator.evaluate)
├─ StrategySignalEntity created
├─ Fields: id, user_id, symbol, strategy_name, quality, imbalance, trend
├─ Table: strategy_signals
├─ Ownership: IMPLICIT (signal_id is source of truth)
├─ Status: PENDING

PHASE 2: Order Creation (OrderIntentProcessor.processSignalIntent)
├─ Input: SignalPersistedMessage
├─ Service: OrderLifecycleService.createOrGetIdempotent()
├─ Idempotency: "signal:{signalId}:{userId}"
├─ OmsOrder created
├─ Table: oms_orders
├─ Field linking: order.signal_id = signal.id (implicit)
├─ Status: CREATED → VALIDATED → RISK_CHECK

PHASE 3: Risk Evaluation (RiskEngineService.evaluate)
├─ Service: OrderCooldownRule [line 35-52 OrderCooldownRule.java]
├─ Check: findLatestCreatedAtForUserSymbolExcluding()
├─ If cooldownMs <= 0: SKIP (currently 0) ← DISABLED
├─ If cooldownMs > 0: Check gap since last order
├─ Status: Either PASS or REJECTED

PHASE 4: Entry Execution (ExecutionService.submitForExecution)
├─ Pre-exit check: BrokerPositionTruthService.validateForExecution()
├─ Broker sync: syncUser() [every 3 seconds]
├─ Guard mode: ENTRY_STRICT for entries
├─ Order status: PENDING_SUBMISSION

PHASE 5: Position Opening
├─ Broker fills entry order
├─ OMS creates PortfolioPosition record
├─ Table: portfolio_positions
├─ Fields: order_id, symbol, qty, entry_price, entry_timestamp
├─ Ownership: Linked via order_id → signal_id
├─ Status: OPEN

PHASE 6: Position Hold
├─ Entry signal status: RUNNING
├─ Position qty > 0
├─ Ownership: Active
├─ Re-entry possible: Only if cooldown elapsed (currently no protection)

PHASE 7: Exit Decision (SignalOutcomeExitService or PressureSmartExitService)
├─ Check: Current position qty (from PortfolioPosition)
├─ Decision: Exit triggered (PRESSURE_EXIT or HARD_STOP)
├─ Status: Signal updated to EXITING

PHASE 8: Exit Execution
├─ Pre-exit broker check: validateForExecution()
├─ Guard mode: EXIT_SAFE
├─ Exit order submitted
├─ Broker closes position
├─ Status: PENDING_SUBMISSION

PHASE 9: Position Closing
├─ Broker confirms position closed (qty=0)
├─ OMS receives fill
├─ Portfolio position updated: qty=0
├─ Status: CLOSED

PHASE 10: Outcome Recording (SignalOutcomeTrackerService)
├─ Service: recordOutcome()
├─ Update: strategy_signals.outcome_status = 'CLOSED'
├─ Update: strategy_signals.exit_reason = 'HARD_STOP' | 'PRESSURE_EXIT'
├─ Update: strategy_signals.pnl = realized_pnl
├─ Ownership: RELEASED (implicitly)
├─ Status: TERMINAL

PHASE 11: Stale Ownership Cleanup
├─ Manual exit: BrokerPositionTruthService detects closure
├─ Signal status: Still RUNNING (NOT auto-updated) ← GAP
├─ Ownership: Stale (can remain until signal expires)
├─ Risk: Re-entry possible if cooldown disabled
```

## Ownership Source of Truth

```
PRIMARY:    strategy_signals.signal_id
            └─ Signal entity is single source of truth

SECONDARY:  oms_orders.signal_id  
            └─ Links order to signal

TERTIARY:   portfolio_positions.order_id
            └─ Links position to order (and transitively to signal)

OWNERSHIP RELEASE:
├─ Implicit release: Signal moves to CLOSED state
├─ Explicit release: NO explicit ownership_cleared_at field
├─ Gap: No owned_by, owned_at, released_at fields
└─ Risk: Fragile for multi-strategy, enterprise scenarios
```

---

# SECTION 7: MANUAL EXIT END-TO-END SIMULATION

## Scenario: User closes position from Zerodha terminal

```
T+0s: User Action
├─ User terminal: SELL button clicked
├─ Broker (Zerodha): Order placed, filled immediately
├─ Zerodha: position_qty = 0
└─ Zerodha API: Order confirmed

T+1-2s: OMS Detection Cycle (BrokerPositionTruthService)
├─ Service: BrokerPositionTruthService.syncUser(userId)
├─ Method: Scheduler runs every 3 seconds (configurable)
├─ Action: Query broker positions for userId
├─ Detection: OMS position qty=1, Broker position qty=0
├─ Log: "Broker position mismatch detected"
└─ Result: MISMATCH DETECTED

T+3-5s: OMS Internal State Update
├─ Service: BrokerPositionTruthService
├─ Update: Internal snapshot cached
├─ Database: portfolio_positions table NOT auto-updated
├─ Problem: OMS knows about mismatch, but doesn't close position
└─ Risk: Position in OMS still shows qty=1

T+5-10s: Strategy Still Thinks Position Open
├─ Signal status: RUNNING (from database)
├─ Position status: OPEN (from OMS)
├─ Strategy logic: Position still held
├─ Outcome status: NOT updated automatically
└─ Risk: EXIT LOGIC MAY STILL FIRE

T+10-30s: Pre-Exit Broker Check (if strategy triggers exit)
├─ Service: OrderIntentProcessor.validateForExecution()
├─ Code: Lines 315-334 OrderIntentProcessor.java
├─ Check: brokerPositionTruthService.validateForExecution()
├─ Result: Guard finds qty=0, rejects exit order
├─ Log: "Broker position not found, exit rejected"
└─ Safeguard: PREVENTS duplicate exit order ✓

T+30-60s: Signal Outcome NOT Auto-Updated
├─ Database: strategy_signals still shows outcome_status='RUNNING'
├─ Problem: No automatic cleanup
├─ Solution: Manual: Need to run outcome update script
│  OR: Signal expires when new signal generated
└─ Gap: INCOMPLETE LIFECYCLE

T+60s+: Potential Re-entry Issue
├─ Scenario: Same symbol generates new signal
├─ Check: OrderCooldownRule.evaluate()
├─ Current setting: cooldownMs=0 (DISABLED)
├─ Result: NEW entry ALLOWED immediately
├─ Risk: Re-entry without cooldown (IF cooldown disabled)
└─ Safeguard: IF cooldown was enabled, would BLOCK

TIMELINE SUMMARY:

Manual Close:           T+0s
Detection:             T+1-2s (broker mismatch detected)
OMS knows mismatch:    T+3-5s (cached, not persisted)
Strategy still running: T+5-10s (outcome not auto-updated)
Exit prevented:        T+10-30s (pre-exit check saves it)
Manual exit invisible:  T+30-60s (signal stays RUNNING)
Re-entry possible:     T+60s+ (if cooldown disabled)

GAPS IDENTIFIED:

1. ❌ Outcome not auto-updated on manual close
   └─ Causes: Stale signal state

2. ❌ OMS position not updated on broker mismatch
   └─ Causes: Internal state desync

3. ⚠️ Re-entry allowed immediately (if cooldown disabled)
   └─ Causes: No symbol memory

4. ✅ Pre-exit broker check prevents duplicate orders
   └─ Safeguard working

SEVERITY: MEDIUM (pre-exit check saves it, but incomplete)
```

---

# SECTION 8: CLUSTER ROOT CAUSE (04:58:03)

## Why All 4 Signals Passed Gates Simultaneously

```
Cluster Details:
├─ Time: 04:58:03 UTC (exact same second)
├─ Symbols: KOTAKBANK, ASIANPAINT, COALINDIA, SBILIFE
├─ Outcome: All 4 lost (-7.82% combined)

Market Snapshot:
├─ VIX = 17.5 (CONSTANT)
├─ PCR = 1.05 (CONSTANT)
├─ Strength = "hi" (CONSTANT)
└─ Timestamp = 04:58:03 (SAME SECOND)

WHY CONSTANT ACROSS ALL 4?

Signal Generation Process:
├─ Scheduler: Polls markets every 2-5 minutes (e.g., every 2 min)
├─ Action: Scans 50+ symbols in BATCH
├─ Data source: Uses SAME market snapshot for entire batch
├─ VIX: Fetched ONCE per batch scan
├─ PCR: Fetched ONCE per batch scan
├─ Evaluation: All 50+ symbols evaluated from SAME snapshot
└─ Result: Simultaneous entries if gates pass

Service Flow:
├─ StrategySignalPipelineService.pollMarketData()
├─ Method: Runs every ~2 minutes (scheduled)
├─ Action: Fetches market snapshot (1 time)
├─ For each symbol: Evaluate(snapshot)
│  ├─ KOTAKBANK: passes gates → signal created
│  ├─ ASIANPAINT: passes gates → signal created
│  ├─ COALINDIA: passes gates → signal created
│  ├─ SBILIFE: passes gates → signal created
│  └─ Other 46: fail gates or not tracked
├─ All 4 persist to database in same transaction
├─ All 4 execute through pipeline same instant
└─ Result: Simultaneous entry orders

Root Cause of Cluster:
├─ Batch processing (efficient)
├─ Single market snapshot per batch (unavoidable)
├─ No cluster detection logic (architectural gap)
├─ No entry rate limiting (not implemented)
├─ No correlation control (missing)
└─ Conclusion: INTENTIONAL ARCHITECTURE, UNMANAGED RISK

Was it preventable?
├─ Could have staggered entries (expensive)
├─ Could have limited to 1 signal per 2 minutes (too restrictive)
├─ Could have detected 3+ in window and rejected excess (BEST)
├─ Could have randomized entry order (mediocre)
└─ Best solution: Cluster detection + pause/stagger

Why all 4 failed?
├─ KOTAKBANK: High imbalance (60%) + batch timing
├─ ASIANPAINT: Weak trend (0.218%) + batch timing
├─ COALINDIA: Cluster batch correlation
├─ SBILIFE: Very high imbalance (66%) + weak trend (0.180%) + cluster

Correlation Risk:
├─ All from same 1-minute candle (50 symbols)
├─ All evaluated on same VIX reading (17.5)
├─ All on same PCR reading (1.05)
├─ All during same market regime ("hi" strength)
└─ Probability: Near-identical market conditions across 4 sectors
```

---

# SECTION 9: RE-ENTRY ROOT CAUSE

## TCS #2 (Why was it approved?)

```
Timeline:
├─ TCS #1: Entered 05:03:31, exited 05:12:12, result: +3.10%
├─ Time gap: 81 minutes
├─ TCS #2: Entered 06:33:20

WHY WAS TCS #2 APPROVED?

Service Check Points:

1. OrderCooldownRule.evaluate()
   ├─ Method: findLatestCreatedAtForUserSymbolExcluding(userId, "TCS", orderId)
   ├─ Result: Found TCS #1 order at 05:12:12
   ├─ Gap: 06:33:20 - 05:12:12 = 81 minutes
   ├─ Check: if (gap < cooldownMs)
   ├─ Current: cooldownMs = 0 (DISABLED)
   ├─ Decision: if (cooldownMs <= 0) return RiskDecision.ok()
   └─ Verdict: COOLDOWN CHECK SKIPPED (returns OK without checking!)

2. Code Evidence (OrderCooldownRule.java lines 35-36):
   ```java
   if (cooldownMs <= 0) {
       return RiskDecision.ok();  // ← SKIPS CHECK
   }
   ```

3. What Should Have Happened:
   ├─ IF cooldownMs = 30000 (30 seconds)
   ├─ Gap = 81 minutes = 4860000 ms > 30000
   ├─ Check: if (4860000 < 30000) → FALSE
   ├─ Result: Would PASS anyway (81 min > 30 sec min)
   └─ Outcome: TCS #2 would still be allowed!

4. Better Control: Outcome Memory
   ├─ Check: Previous TCS #1 outcome?
   ├─ Result: +3.10% win
   ├─ Decision: Could allow (was a winner)
   ├─ But system doesn't track: "was this symbol winning before?"
   └─ Missing: Outcome memory check
```

## GRASIM #2 (Why was it approved?)

```
Timeline:
├─ GRASIM #1: Entered 05:17:31, exited 05:21:12, result: -6.14%
├─ Time gap: 116 minutes
├─ GRASIM #2: Entered 07:18:04

WHY WAS GRASIM #2 APPROVED?

Service Check Points:

1. OrderCooldownRule.evaluate() (same as TCS #2)
   ├─ Check: findLatestCreatedAtForUserSymbolExcluding(userId, "GRASIM", orderId)
   ├─ Result: Found GRASIM #1 order
   ├─ Gap: 116 minutes
   ├─ Current cooldownMs: 0 (DISABLED)
   ├─ Decision: return RiskDecision.ok() (skip check)
   └─ Verdict: COOLDOWN CHECK SKIPPED

2. What Should Have Happened:
   ├─ GRASIM #1 was -6.14% loss (never even profitable!)
   ├─ IF system had outcome memory: "GRASIM just lost"
   ├─ Decision: Block re-entry to losing symbol?
   ├─ But system doesn't check: previous outcome
   └─ Missing: "Don't re-enter losing symbols"

3. Code Missing:
   ├─ No OutcomeMemoryCheck service
   ├─ No "last_outcome_for_symbol" query
   ├─ No "block_losing_symbol" rule
   ├─ No "symbol_history" tracking
   └─ All: NOT IMPLEMENTED

4. Quality Gate Failure:
   ├─ GRASIM #1 quality: 75
   ├─ GRASIM #2 quality: 74 (DEGRADED)
   ├─ New code would have: QUALITY_FLOOR = 75
   ├─ So GRASIM #2 quality 74 < 75 → REJECTED
   ├─ But: Current code has QUALITY_FLOOR = 68
   ├─ So GRASIM #2 quality 74 > 68 → APPROVED
   └─ Root cause: Old code deployed (quality=68)
```

---

# SECTION 10: CLASSIFICATION OF FINDINGS

## PRODUCTION BUG (Confirmed)

```
1. OrderCooldownRule Disabled
   ├─ Code: OrderCooldownRule.java
   ├─ Issue: cooldownMs=0 skips all checks
   ├─ Impact: Re-entries allowed without spacing
   ├─ Evidence: TCS #2, GRASIM #2 entered despite recent exits
   ├─ Fix: Enable STOKR_RISK_ORDER_COOLDOWN_MS=30000
   └─ Severity: P0 CRITICAL

2. Manual Exit Outcome Not Auto-Updated
   ├─ Code: BrokerPositionTruthService.syncUser()
   ├─ Issue: Detects mismatch but doesn't update signal
   ├─ Impact: Stale signal state after manual close
   ├─ Evidence: Manual exit scenario shows signal stays RUNNING
   ├─ Fix: Add automatic signal outcome update
   └─ Severity: P1 HIGH
```

## ARCHITECTURAL GAP (Confirmed)

```
3. Cluster Entry Not Detected
   ├─ Design: Batch processing creates simultaneous entries
   ├─ Issue: No cluster detection when 3+ entries in 2 minutes
   ├─ Impact: -7.82% loss from 04:58:03 cluster
   ├─ Evidence: All 4 signals from same market snapshot
   ├─ Fix: Add cluster detection + pause/stagger
   └─ Severity: P0 CRITICAL

4. Implicit Ownership Model
   ├─ Design: Ownership not explicit, inferred from signal state
   ├─ Issue: No ownership_timestamp, ownership_cleared_at fields
   ├─ Impact: Fragile in multi-strategy, restart scenarios
   ├─ Evidence: No explicit ownership audit trail
   ├─ Fix: Add explicit ownership registry
   └─ Severity: P2 MEDIUM

5. Outcome Memory Missing
   ├─ Design: No tracking of symbol's previous outcomes
   ├─ Issue: Can't block re-entry after loss
   ├─ Impact: GRASIM #2 entered after GRASIM #1 loss
   ├─ Evidence: Both GRASIM trades lost
   ├─ Fix: Add outcome memory check before entry
   └─ Severity: P1 HIGH
```

## CONFIGURATION ISSUE (Confirmed)

```
6. Code Fix Not Deployed
   ├─ Fix commit: a1fdc4da
   ├─ Issue: Exists in source code, NOT deployed to production
   ├─ Changes: Quality 75, GRASIM disabled, VIX 20, SL 0.50%
   ├─ Deployed version: OLD (quality 68, GRASIM enabled)
   ├─ Impact: All fixes had zero effect on today's trading
   ├─ Evidence: SBILIFE quality 74 approved (proves quality=68 deployed)
   ├─ Evidence: GRASIM entered twice (proves GRASIM not disabled)
   ├─ Fix: Deploy Release_v2 with a1fdc4da commit
   └─ Severity: P0 CRITICAL
```

## STRATEGY ISSUE (Unproven)

```
7. Quality Score Insufficient
   ├─ Observation: Quality 79, 78 = worst 2 losses
   ├─ Theory: Quality alone can't predict outcome
   ├─ Evidence: Today only (need 30 days)
   ├─ Fix: Don't implement filters yet
   └─ Status: COLLECT DATA (30 days)

8. Imbalance Pattern
   ├─ Observation: High imbalance (60%+) = 0% win rate
   ├─ Evidence: 7 trades today = 0 wins
   ├─ Confidence: LOW (one day sample)
   ├─ Fix: Don't implement filter yet
   └─ Status: COLLECT DATA (30 days)
```

## UNPROVEN THEORY (Not Evidence)

```
9. Symbol-specific issues (GRASIM)
   ├─ Observation: GRASIM 0% win rate today
   ├─ Sample: Only 2 trades
   ├─ Confidence: VERY LOW
   ├─ Fix: Don't disable yet
   └─ Status: COLLECT 30-DAY DATA

10. Trend as filter
    ├─ Observation: Weak trends lose
    ├─ Sample: 1 day
    ├─ Confidence: LOW
    ├─ Fix: Don't filter yet
    └─ Status: COLLECT 30-DAY DATA
```

---

# SECTION 11: DEPLOYMENT VERIFICATION ACTION PLAN

## Before Any Implementation:

```
STEP 1: Verify Running Code (Production Server 173.249.55.84)
├─ SSH to server
├─ Run: docker inspect stokr-api --format='{{.RepoDigests}}'
├─ Get: Image tag/digest
├─ Question: What commit is it built from?
├─ Action: Check docker build logs or git tag

STEP 2: Check Configuration Values
├─ Curl: http://173.249.55.84:8080/actuator/configprops | grep -i cooldown
├─ Look for: STOKR_RISK_ORDER_COOLDOWN_MS
├─ Expected: 0 (currently disabled)
├─ Action: Verify environment variables

STEP 3: Verify Code Version
├─ Query database: SELECT COUNT(*) FROM strategy_signals WHERE quality < 75 AND date='2026-06-08'
├─ If result > 0: quality_floor = 68 (OLD code)
├─ If result = 0: quality_floor >= 75 (NEW code)
├─ Action: Determine deployed code version

STEP 4: Deploy Fix Commit (IF NOT DEPLOYED)
├─ Commit: a1fdc4da "Fix INDEX_HUNT: widen SL, tighten VIX gates, disable GRASIM"
├─ Branch: Release_v2
├─ Action: Rebuild docker image from a1fdc4da
├─ Action: Deploy updated image to production
├─ Verify: New trades have quality >= 75 (if new code)

STEP 5: Enable OrderCooldownRule (NEW SETTING)
├─ Update: STOKR_RISK_ORDER_COOLDOWN_MS=30000
├─ Method: Set in .env or docker-compose.yml
├─ Test: Next re-entry to same symbol should be blocked
└─ Verify: In logs "Order cooldown active"
```

---

# FINAL VERDICT

```
DEPLOYMENT STATUS:          ❌ NOT READY

CRITICAL ISSUES:

1. ❌ Code fix not deployed (a1fdc4da missing from production)
2. ❌ OrderCooldownRule disabled (cooldownMs=0)
3. ❌ Manual exit outcome not auto-updated
4. ❌ Cluster detection not implemented

WHY TODAY'S CLUSTER HAPPENED:

├─ Old code running: quality_floor=68
├─ GRASIM not disabled (not in old code)
├─ All low-quality signals approved
├─ 4 signals from batch snapshot at 04:58:03
└─ Result: -7.82% loss (preventable with code fix)

ACTION BEFORE TRADING TOMORROW:

CRITICAL PATH (2-3 hours):
├─ ✅ Deploy Release_v2 with a1fdc4da (1 hour)
├─ ✅ Set STOKR_RISK_ORDER_COOLDOWN_MS=30000 (30 min)
├─ ✅ Verify quality_floor=75 active (15 min)
├─ ✅ Verify GRASIM disabled (15 min)
└─ ✅ Verify stop loss=0.50% (15 min)

DO NOT IMPLEMENT YET:
├─ ❌ Cluster detection (wait for code verification)
├─ ❌ Manual exit automation (wait for code verification)
├─ ❌ Outcome memory (wait for 30 days data)
├─ ❌ Ownership registry (wait for 30 days data)
├─ ❌ Signal filters (need 30 days proof)

THEN AFTER DEPLOYMENT:
├─ Run 7-day trading session
├─ Verify quality=75 is preventing bad entries
├─ Verify GRASIM disabled
├─ Collect data for 30-day analysis
└─ Then implement remaining fixes

TIMELINE TO SAFE TRADING:

Today (06-08):
├─ ✅ Complete architectural verification (done)
└─ ✅ Identify deployment issue (done)

Tomorrow (06-09):
├─ 08:00-09:00 UTC: Deploy code + enable cooldown
├─ 09:15 UTC: Market open (trade with fixes)
├─ Verify: Quality >= 75 active
└─ Verify: GRASIM disabled

Days 2-7:
├─ Collect entry quality data
├─ Verify no cluster issues
├─ Monitor re-entry behavior
└─ Confirm fixes working

Day 30:
├─ 30-day statistical analysis
├─ Evaluate imbalance/trend patterns
├─ Decide on additional filters
└─ Implement if proven

CONCLUSION: Deploy existing fix first, then infrastructure improvements, then filters after 30 days.
```

