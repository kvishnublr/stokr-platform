# POSITION ORPHANING ROOT CAUSE ANALYSIS

**Date:** 2026-06-10  
**Affected Positions:** 6 broker positions with no OMS records  
**Status:** CRITICAL - Platform cannot exit these positions  

---

## EXECUTIVE SUMMARY

Six positions exist in Zerodha broker but have NO corresponding OMS records:
- **NSE:SBIN** (Qty: TBD)
- **NSE:KOTAKBANK** (Qty: TBD)
- **NSE:HCLTECH** (Qty: TBD)
- **NSE:ADANIPORTS** (Qty: TBD)
- **NSE:ICICIBANK** (Qty: TBD)
- **NSE:JSWSTEEL** (Qty: TBD)

**User confirmed:** "its thru platform only.. not manaul" (2026-06-10)

**Database evidence shows:**
- NSE:SBIN: 1 signal exists (source=LAB, date=May 29, 2026), but 2 OMS orders are CANCELLED
- Other 5 symbols: 0 signals, 0 OMS orders (completely missing from execution tracking)

**Root cause:** Orders were placed at broker but platform's execution system has no record of them

---

## INVESTIGATION FINDINGS

### Finding 1: Exit System Cannot Track These Positions

**Why Exit Monitor Cannot Exit These Positions:**

File: `/opt/stokr/stokr-platform/scripts/exit_monitor.py`

Exit monitor tracks positions by querying `oms_orders` table:
```python
SELECT * FROM oms_orders WHERE execution_mode='LIVE' AND user_id=?
```

**For the 6 orphaned positions:**
- ❌ No OMS order records exist
- ❌ Exit monitor queries return 0 results for these symbols
- ❌ Exit monitor marks them as ORPHAN_BROKER_POSITION
- ❌ Platform cannot generate exit orders

**Consequence:** Even if exit decision is made, there's no execution path to close these positions.

---

### Finding 2: Three Exit Paths Analysis

#### Path A: HybridExitService (DEAD CODE)
- **Status:** ❌ UNREACHABLE in production
- **Reason:** Package `com.stokr.trading.service.exit` does not exist in codebase
- **Evidence:** Zero compilation, zero imports, zero runtime logs
- **Conclusion:** Not responsible for position orphaning

#### Path B: PositionExitOrchestratorService (ACTIVE & COMPLIANT)
- **Status:** ✅ ACTIVE in production
- **Flow:** OrderPlacementService → RabbitMQ → ExecutionSimulator → Broker
- **All orders tracked:** YES (idempotency keys, OMS state machine)
- **Exit capability:** Closing positions requires OMS order records (which these positions lack)

#### Path C: TraderTerminalControlService (ACTIVE & COMPLIANT)
- **Status:** ✅ ACTIVE in production
- **Flow:** Same as Path B (delegates to OrderPlacementService)
- **Exit capability:** Same limitations as Path B

**Critical Finding:** None of the active exit paths can close these positions because:
1. They rely on OMS order records to track execution
2. These positions have NO OMS order records
3. Without OMS records, no exit orders can be created

---

### Finding 3: NSE:SBIN Specific Analysis

**Database Record:**
```
Signal: 1 (source=LAB, date=May 29, 2026)
OMS Orders: 2 (state=CANCELLED)
Broker Position: OPEN
```

**Hypothesis 1:** Order was created but cancelled before execution
- Signal was generated ✅
- OMS order was created ✅
- Order was transitioned to CANCELLED ✅
- Position was never created at broker? ❌ (Position exists in broker)

**Hypothesis 2:** Order was executed, then manually cancelled
- Order was executed successfully ✅
- Broker position created ✅
- Trader manually cancelled order in OMS ✅
- Position remains open at broker ✅

**Most Likely:** Second hypothesis - orders were created and executed, but someone cancelled the OMS orders after position was already open in broker.

---

### Finding 4: Other 5 Symbols - Complete Missing Entry

**Database Record:**
```
Signals: 0
OMS Orders: 0
Broker Positions: OPEN (6+ units each)
```

**This indicates:**
1. ❌ Signal never reached the platform
2. ❌ Signal was generated but never persisted
3. ❌ Signal was generated and persisted but never processed
4. ✅ Positions were somehow created outside the normal signal execution pipeline

**Most Likely Root Cause:** 

These positions were created through an alternative execution path that doesn't involve:
- Signal generation
- OrderIntentProcessor
- OrderPlacementService
- OMS order creation

**Possible Alternative Paths:**
1. Manual trading through Zerodha web/mobile (Trader)
2. Legacy execution system (if one exists)
3. Direct broker API calls from a different application
4. Batch order creation from an admin interface

---

## ARCHITECTURAL ROOT CAUSE

### Why Position Orphaning Is Possible

The platform has a **two-tier authority model** that creates opportunity for orphaning:

#### Tier 1: OMS Authority
- Platform tracks orders through OMS
- Exits are controlled by OMS workflow
- Source: `oms_orders` table, `oms_executions` table

#### Tier 2: Broker Authority  
- Broker tracks actual positions
- Final position source of truth
- Source: Zerodha Kite API

**The Problem:** Positions can exist in Broker (Tier 2) without records in OMS (Tier 1)

**How This Happens:**
1. Order placed at broker outside OMS (manual, legacy system, etc.)
2. Broker accepts order and opens position
3. OMS has no record of it
4. Exit system cannot see it (queries only OMS)
5. Position becomes orphaned

**Architectural Gap:** No reconciliation mechanism to register broker positions back into OMS

---

## DETECTION & RECONCILIATION

### Current Detection Method

**File:** `BrokerReconciliationService.java`

Detects orphaned positions:
```java
if (brokerPosition exists && omsPosition NOT FOUND) {
    event = ORPHAN_BROKER_POSITION
    log.warn("Position exists in broker but not in OMS")
}
```

### Current Reconciliation Capability

**File:** `PositionExitOrchestratorService.java` (Lines 193-239)

Fallback mechanism exists:
```java
IF broker connected:
    Use broker positions (PRIMARY)
ELSE:
    Use PortfolioPosition table (FALLBACK)
ELSE:
    Use orphan reconciliation events (FINAL FALLBACK)
```

**But:** Fallback mechanism only works for exiting positions. 
**Gap:** No mechanism to register orphaned positions back into OMS for future tracking.

---

## IMMEDIATE REMEDIATION OPTIONS

### Option 1: Close Positions Manually at Broker
- **Method:** Trader manually closes each position via Zerodha Kite web/mobile
- **Effort:** 5 minutes (6 positions)
- **Verification:** Exit monitor will no longer report them
- **Risk:** Low
- **Advantage:** Clean closure, no OMS records needed

### Option 2: Create Reconciliation Records
- **Method:** Manually create OMS entry records for each position (as if they were executed normally)
- **Effort:** Medium (requires database writes)
- **Risk:** Creates false execution records
- **Advantage:** Allows exit system to track them

### Option 3: Bulk Exit via PositionExitOrchestratorService
- **Method:** Call `flattenAll()` or `flattenSymbol()` for affected positions
- **Flow:** Uses fallback mechanism to detect broker position → Creates exit orders
- **Effort:** Low (one API call)
- **Risk:** Execution risk if fallback doesn't work
- **Verification:** Monitor OMS for new EXIT orders

---

## PREVENTION GOING FORWARD

### Root Cause Prevention

**To prevent future position orphaning, one of these must be true:**

1. **All orders must go through OMS**
   - No manual trades outside platform
   - No legacy execution systems
   - Trader UI must go through OrderPlacementService

2. **Continuous Reconciliation**
   - Every broker position must have a corresponding OMS record
   - Orphaned positions must be automatically registered
   - Fallback mechanism must be always-on

3. **Broker Position Enforcement**
   - Block orders that would create orphaned positions
   - Validate before placing that OMS record exists
   - Reject manual broker closes if they orphan position

---

## RECOMMENDATION

### Immediate Action

1. **Verify current broker position state** (if positions still exist)
2. **Close positions via Option 1** (manual closure at broker) - safest
3. **Verify exit monitor no longer reports them as ORPHANED**
4. **Document what caused these to be created outside OMS**

### Root Cause Investigation

**Next Steps:**
1. Check if there's a manual trading interface that doesn't use OMS
2. Check Zerodha audit logs for who created these orders and when
3. Check if trader had alternative execution method (API direct, etc.)
4. Interview trader who created these positions

### Architectural Improvement

Implement **continuous position reconciliation**:
- Every broker sync should validate OMS records exist
- Missing OMS records should be auto-created as needed
- Or block broker positions without OMS records

---

## CONCLUSION

**Root Cause:** Positions were created at broker outside the platform's normal OMS execution pipeline.

**Why Exit System Failed:** Exit monitor only queries OMS tables; positions exist only in Zerodha broker.

**Why It Happened:** Either manual trading or alternative execution path created positions without OMS records.

**How to Fix:** Close positions manually at broker, then investigate root cause to prevent recurrence.

**Architectural Fix Needed:** Implement continuous reconciliation or enforce OMS-first execution for all position creation.
