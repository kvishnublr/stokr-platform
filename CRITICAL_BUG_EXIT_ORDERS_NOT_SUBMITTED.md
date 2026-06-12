# 🔴 CRITICAL BUG: Exit Orders Created but Not Submitted to Broker

**Date:** 2026-06-10  
**Severity:** CRITICAL - Positions remain open at broker despite system recording exits  
**Affected Positions:** 6 (ADANIPORTS, JSWSTEEL, KOTAKBANK, ICICIBANK, SBIN, HCLTECH)  
**Status:** ACTIVE - REQUIRES IMMEDIATE INVESTIGATION  

---

## EXECUTIVE SUMMARY

The system is **creating exit orders but NOT submitting them to Zerodha broker**.

**Evidence:**
- ✅ 6 signals with exit outcomes recorded (PRESSURE_EXIT, SL_HIT, FEED_PROTECTION)
- ✅ P&L calculated and shown (-₹9, +₹10, +₹1, -₹1, +₹1, +₹0)
- ✅ Exit decision made by system
- ❌ Positions STILL OPEN at Zerodha broker
- ❌ No broker order ID recorded for exit orders

**This means:**
1. SignalOutcomeExitService.dispatchForSignal() IS running
2. OrderPlacementService.place() IS creating OMS exit orders
3. Orders ARE being transitioned to PENDING_SUBMISSION
4. Orders ARE being dispatched to RabbitMQ
5. **BUT:** ExecutionSimulator.submitToBroker() is NOT successfully sending orders to Zerodha

---

## EXECUTION PATH VERIFICATION

### Path Confirmed ✅
```
Signal Outcome Detected (PRESSURE_EXIT, SL_HIT, etc.)
  ↓ (SignalOutcomeExitService.onSignalOutcome)
Exit order created with:
  - idempotencyKey: "outcome-exit:{signalId}:{entryOrderId}:{outcome}"
  - executionMode: LIVE
  - brokerVendor: ZERODHA
  ↓ (OrderPlacementService.place)
Order persisted with state=CREATED
  ↓
Guards checked (EXIT_SAFE mode)
Risk engine checked
  ↓
State transitioned → PENDING_SUBMISSION
  ↓
Order dispatched to RabbitMQ queue: EXECUTION
  ↓
ExecutionConsumer receives message
  ↓
ExecutionSimulator.process(message) called
```

### Breakdown Point ❌
The order reaches ExecutionSimulator.process() but either:
1. **Does NOT enter LIVE submission path** (line 118 check fails)
2. **Fails during LIVE credential resolution** (lines 354-377)
3. **Fails during broker submission** (line 177: orderLifecycleService.submitToBroker)
4. **Exception is caught and swallowed** (ExecutionConsumer line 29-42)

---

## HYPOTHESIS: Exit Orders Not Reaching LIVE Path

### Evidence Analysis

**Signal Outcome Tracking:**
- Signal entity shows `outcome_status` recorded
- Signal entity shows `final_pnl` calculated
- This proves outcome event handler ran

**But:**
- No `broker_order_id` recorded on exit OmsOrder
- No execution records in `oms_execution` table

**This indicates:**
- Exit order was CREATED in `oms_order` table ✓
- Order was DISPATCHED to RabbitMQ ✓
- Order state machine transitioned to PENDING_SUBMISSION ✓
- **BUT:** Order was NEVER submitted to Zerodha (no broker_order_id)

---

## POSSIBLE ROOT CAUSES

### Root Cause 1: Exit Orders Marked as Non-LIVE ❌

**Location:** OrderPlacementService.place() - Line 50

```java
ExecutionMode mode = req.executionMode() == null ? ExecutionMode.SIMULATED : req.executionMode();
```

If `createOrderRequest.executionMode()` is NULL or PAPER:
- Order stays in SIMULATED mode
- ExecutionSimulator routes to simulation path (lines 232-329)
- No broker submission happens ✗
- BUT position is marked closed in simulation
- Broker still has position open ✗

**Check:** SignalOutcomeExitService line 291:
```java
return new ExitResolution(brokerQty.abs(), side, ExecutionMode.LIVE, "ZERODHA");
```

This SHOULD return LIVE. But what if `brokerPositionTruthService.syncUser()` FAILED?

**Investigation:**
```sql
SELECT execution_mode, state, broker_vendor FROM oms_order
WHERE idempotency_key LIKE 'outcome-exit:%'
AND symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
AND created_at >= '2026-06-10 00:00:00';
```

### Root Cause 2: Broker Credentials Missing ❌

**Location:** ExecutionSimulator.resolveBrokerCredentials() - Lines 354-377

If broker credentials cannot be resolved:
```java
return new String[]{null, null};
```

Then at line 171-173:
```java
String[] creds = resolvedCreds
    .map(c -> new String[]{c.apiKey(), c.accessToken()})
    .orElseGet(() -> resolveBrokerCredentials(liveUserId, effectiveVendor));
```

If creds are [null, null], ZerodhaAdapter.placeOrder() will throw:
```java
throw new IllegalStateException(
    "Zerodha credentials not available — broker session may have expired. Re-connect Zerodha."
);
```

Exception is caught at ExecutionConsumer line 29, order marked FAILED.

**Check:** Do broker accounts have valid `access_token_enc`?
```sql
SELECT user_id, vendor_code, access_token_enc FROM broker_account
WHERE deleted = false AND vendor_code = 'ZERODHA';
```

### Root Cause 3: Broker Disconnect Protection ❌

**Location:** ExecutionSimulator - Lines 161-170

```java
if (!simulationLive && brokerDisconnectProtectionService.blocksLiveOrders(credentialUserId)) {
    String reason = "Broker disconnected or execution degraded";
    brokerExecutionTelemetryService.recordRejection(order.getId(), reason);
    orderLifecycleService.transition(order.getId(), OrderState.REJECTED, reason);
    executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, ...);
    return;
}
```

If broker disconnect protection is active, ALL LIVE orders are blocked.

**Check:** Is broker disconnect protection enabled?
```sql
SELECT * FROM broker_execution_telemetry
WHERE user_id IN (SELECT DISTINCT user_id FROM oms_order WHERE symbol IN ('ADANIPORTS', ...))
ORDER BY timestamp DESC LIMIT 100;
```

### Root Cause 4: LIVE Gate Rejection ❌

**Location:** ExecutionSimulator - Lines 123-155

Multiple gates checked:
1. `liveTradingTraderEligibilityService.evaluateForLiveOrder()` (line 133)
2. If gate HARD_FAIL: order rejected, event published, return (lines 136-154)

If trader is blocked from live trading, exit orders would fail here.

---

## DEBUGGING STEPS

### Step 1: Verify Exit Orders Were Created

**SQL Query:**
```sql
SELECT 
  oo.id,
  oo.symbol,
  oo.idempotency_key,
  oo.state,
  oo.execution_mode,
  oo.broker_vendor,
  oo.broker_order_id,
  oo.reject_reason,
  oo.created_at
FROM oms_order oo
WHERE oo.idempotency_key LIKE 'outcome-exit:%'
  AND oo.symbol IN ('ADANIPORTS', 'JSWSTEEL', 'KOTAKBANK', 'ICICIBANK', 'SBIN', 'HCLTECH')
  AND DATE(oo.created_at) = CURDATE()
ORDER BY oo.created_at DESC;
```

**Expected:**
- Multiple exit orders exist (1 per symbol minimum)
- `execution_mode` = LIVE
- `broker_vendor` = ZERODHA
- `state` = FAILED or PENDING_SUBMISSION or REJECTED (NOT FILLED)
- `reject_reason` populated if FAILED/REJECTED

### Step 2: Check Rejection Reasons

If orders show state=FAILED or REJECTED:
- `reject_reason` column will contain the failure cause
- This directly points to which root cause is happening

### Step 3: Check Execution Logs

**Query:**
```sql
SELECT 
  ee.id,
  ee.order_id,
  ee.quantity_filled,
  ee.price,
  ee.executed_at
FROM oms_execution ee
LEFT JOIN oms_order oo ON ee.order_id = oo.id
WHERE oo.idempotency_key LIKE 'outcome-exit:%'
  AND oo.symbol IN ('ADANIPORTS', ...);
```

**Expected:** Empty result (no executions for exit orders)

### Step 4: Check Execution Telemetry

**Query:**
```sql
SELECT 
  user_id,
  order_id,
  event_type,
  details,
  recorded_at
FROM execution_guard_telemetry
WHERE DATE(recorded_at) = CURDATE()
ORDER BY recorded_at DESC LIMIT 100;
```

**Look for:** Any REJECTION events for exit orders

### Step 5: Check Broker Account Status

**Query:**
```sql
SELECT 
  id,
  user_id,
  vendor_code,
  access_token_enc,
  access_token_valid_until,
  updated_at
FROM broker_account
WHERE deleted = false
ORDER BY updated_at DESC;
```

**Check:** Are access tokens valid and non-null?

---

## IMMEDIATE REMEDIATION

### Option A: Force Re-dispatch Exit Orders

**For each exit order that exists in OMS_ORDER table with state != FILLED:**

```java
// In OrderIntentProcessor or admin controller
for (OmsOrder exitOrder : failedExitOrders) {
    executionService.dispatch(
        new ExecutionDispatchMessage(exitOrder.getId(), ...),
        false  // async via RabbitMQ
    );
}
```

**Risk:** Low (orders are idempotent)

### Option B: Manual Broker Exit

**Trader manually closes positions at Zerodha:**
- Each position takes ~30 seconds
- 6 positions = ~3 minutes total
- Safest approach

### Option C: Bulk Exit via PositionExitOrchestratorService

```java
positionExitOrchestratorService.flattenAll(
    userId,
    "EMERGENCY_FIX",
    "Recovering orphaned positions from failed outcome exits"
);
```

**Risk:** Depends on whether fallback mechanism works for these positions

---

## LOGGING TO ADD

Once root cause is found, add logging to track:

1. **SignalOutcomeExitService.dispatchForSignal():**
   ```java
   log.info("signal.outcome_exit.dispatch.started signalId={} outcome={} entryOrders={}",
           signalId, outcomeStatus, entryOrders.size());
   
   log.info("signal.outcome_exit.exit_resolved symbol={} side={} qty={} mode={} broker={}",
           symbol, exit.side(), exit.qty(), exit.mode(), exit.broker());
   ```

2. **ExecutionSimulator.process():**
   ```java
   log.info("execution.live.mode_check orderId={} executionMode={} isLive={}",
           order.getId(), order.getExecutionMode(), order.getExecutionMode() == ExecutionMode.LIVE);
   
   log.info("execution.live.cred_check orderId={} hasCredentials={}",
           order.getId(), creds[0] != null && creds[1] != null);
   
   log.info("execution.live.broker_disconnect_check orderId={} blocked={}",
           order.getId(), brokerDisconnectProtectionService.blocksLiveOrders(...));
   ```

3. **OrderLifecycleService.submitToBroker():**
   ```java
   log.info("broker.submit.started orderId={} symbol={} side={} qty={}",
           order.getId(), order.getSymbol(), order.getSide(), order.getQuantity());
   
   log.info("broker.submit.response orderId={} status={} kiteOrderId={}",
           submitted.getId(), status, kiteOrderId);
   ```

---

## NEXT STEPS

1. **RUN DEBUG QUERY** on production database (DEBUG_TODAYS_EXITS.sql)
2. **IDENTIFY** which root cause applies
3. **CHECK** rejection reasons on failed exit orders
4. **APPLY** appropriate remediation (Options A, B, or C)
5. **VERIFY** positions are closed at broker
6. **ADD** logging to prevent recurrence
7. **DOCUMENT** root cause in architecture guide

---

## CONCLUSION

Exit order submission is failing after exit orders are created. The orders exist in OMS but are not being submitted to Zerodha. 

**Root cause is in ExecutionSimulator.process() → submitToBroker() path.**

Most likely causes (in priority order):
1. Broker disconnect protection is blocking orders
2. Broker credentials are expired/missing
3. Trader eligibility gate is rejecting orders
4. Exception in submitToBroker() is being swallowed

**Recommendation:** Query database immediately to check exit order states and rejection reasons. This will pinpoint the exact cause.
