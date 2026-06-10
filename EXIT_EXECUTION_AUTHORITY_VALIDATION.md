# EXIT EXECUTION AUTHORITY VALIDATION

**Date:** 2026-06-10  
**Scope:** Complete tracing of exit decision → execution → position closure cycle  
**Conclusion:** CRITICAL AUTHORITY MISMATCH DETECTED

---

## EXECUTIVE SUMMARY

The platform has **THREE DISTINCT EXIT PATHS** with conflicting authority models:

| Path | Entry Point | Order Queue | Broker Submit | Position Authority | Status |
|------|-------------|-------------|---------------|-------------------|--------|
| **Path A: Hybrid** | HybridExitService.executeExit() | ❌ NONE | zerodhaAPI (direct) | HybridExitService (DIRECT) | 🔴 CRITICAL ISSUE |
| **Path B: Orchestrator** | PositionExitOrchestratorService.placeExit() | ✅ RabbitMQ | OrderLifecycleService → BrokerAdapter | BrokerPositionTruthService (synced) | ✅ Compliant |
| **Path C: Terminal** | TraderTerminalControlService.flattenOpenPositions() | ✅ RabbitMQ | OrderLifecycleService → BrokerAdapter | BrokerPositionTruthService (synced) | ✅ Compliant |

---

## DETAILED EXECUTION PATH TRACING

### PATH A: HYBRID EXIT (HybridExitService) - **CRITICAL ANOMALY**

#### Starting Point
**File:** `HybridExitService.java:379`

```java
private void executeExit(Position position, HybridExitDecision decision) {
    // Line 384-390: DIRECT broker API call
    zerodhaAPI.placeMarketOrder(
        position.getSymbol(),
        -position.getQuantity(),
        "BUY",
        "MIS",
        decision.getExitTarget()
    );

    // Line 393: DIRECT position state mutation
    position.setStatus("CLOSED");
    
    // Line 396: DIRECT persistence
    positionRepository.save(position);
}
```

#### Authority Chain
1. **Decision Maker:** `HybridExitService.makeHybridExitDecision()`
   - Logic: Strategy signal → Indicator signals → Dynamic target
   - No risk checks, no execution guards, no OMS involvement
   
2. **Order Creator:** `HybridExitService.executeExit()`
   - **Calls:** `zerodhaAPI.placeMarketOrder()` DIRECTLY (not via OrderPlacementService)
   - No idempotency key generation
   - No order persistence in OMS
   - No state machine enforcement
   
3. **Broker Submission:** Direct Zerodha API call
   - **Authority:** Zerodha broker accepts/rejects
   - No OMS tracking of broker response
   - No order state recorded if broker rejects
   
4. **Position Closure:** `HybridExitService` (UNILATERAL)
   - Position marked CLOSED BEFORE broker confirmation
   - Position marked CLOSED if broker call throws exception (line 400-401 silently catches)
   - No broker position reconciliation
   - Violates BrokerPositionTruthService authority

#### Questions Answered
- **Can HybridExitService directly close a live position?** ✅ YES - It marks position.status="CLOSED" and persists immediately (line 393-396)
- **Does the decision check broker truth?** ❌ NO - No interaction with BrokerPositionTruthService
- **Can a broker order fail silently?** ✅ YES - Exception on zerodhaAPI call is caught (line 400) but position is already marked CLOSED

---

### PATH B: ORCHESTRATOR EXIT (PositionExitOrchestratorService) - **COMPLIANT**

#### Starting Points
- `PositionExitOrchestratorService.flattenAll(userId, actionKey, reason)`
- `PositionExitOrchestratorService.flattenSegment(userId, segment, actionKey, reason)`
- `PositionExitOrchestratorService.flattenSymbol(userId, symbol, actionKey, reason)`

#### Execution Flow

**Stage 1: Broker Truth Sync**
```
PositionExitOrchestratorService:80
  ↓
brokerPositionTruthService.syncUser(userId)  // Fetch live broker state
BrokerPositionTruthSnapshot snap = brokerPositionTruthService.snapshot(userId)
collectTargets(userId, segment, symbolOnly, snap, notes)
```

**Stage 2: Position Target Collection**
```
collectTargets() [Line 193-239]
  ├─ Query broker positions if connected (PRIMARY SOURCE OF TRUTH)
  ├─ Fallback to PortfolioPosition table if no broker sync
  └─ Final fallback to orphan reconciliation events
```

**Stage 3: Exit Order Creation** (Line 159-191)
```java
OmsOrder order = orderPlacementService.place(userId, new CreateOrderRequest(
    target.symbol(),
    side,          // "SELL" or "BUY" based on position direction
    "MARKET",
    qty.abs(),
    null,
    exitMode,      // LIVE vs PAPER vs SIMULATED
    exitBroker,    // "ZERODHA" vs "SIM"
    safeAction,    // "EXIT_SAFE"
    idempotencyKey, // "EXIT_SAFE:" + userId + ":" + symbol
    null, null, null, null,
    true,          // exitOrder = true
    "EXIT_SAFE",   // guardMode
    false, null
));
```

#### Authority Chain

**1. Signal Authority → OrderPlacementService.place()**
```
File: stokr-execution/OrderPlacementService.java:49

Steps:
  a) Create OmsOrder draft with CREATED state
  b) Persist via orderLifecycleService.createOrGetIdempotent()
  c) Transition: CREATED → VALIDATED → RISK_CHECK
  d) FOR LIVE MODE:
     - Resolve ExecutionGuardMode = EXIT_SAFE
     - Create SignalExecutionMetadata
     - Call executionGuardService.validate() [LINE 136]
     - If hard fail: transition to REJECTED [LINE 142]
  e) Call riskEngineService.evaluate() [LINE 155]
     - If rejected: transition to REJECTED [LINE 157]
  f) Transition: RISK_CHECK → PENDING_SUBMISSION [LINE 160]
  g) Dispatch to RabbitMQ via executionService.dispatch() [LINE 177]
  h) Return order in PENDING_SUBMISSION state
```

**2. Queue Processing → ExecutionSimulator.process()**
```
File: stokr-execution/ExecutionConsumer.java:26

ExecutionConsumer listens on PipelineQueues.EXECUTION

For LIVE orders [ExecutionSimulator:118]:
  a) Validate state = PENDING_SUBMISSION
  b) Check LiveTraderEligibility [LINE 127-135]
  c) Check BrokerDisconnectProtection [LINE 161]
  d) Resolve broker credentials [LINE 171-173]
  e) Call orderLifecycleService.submitToBroker() [LINE 177]
```

**3. Broker Submission → OrderLifecycleService.submitToBroker()**
```
File: stokr-oms/OrderLifecycleService.java:148

Transition: PENDING_SUBMISSION → SUBMITTED [LINE 155]

Get BrokerAdapter for vendor [LINE 160]:
  adapter = brokerAdapterRegistry.get("ZERODHA")

Call adapter.placeOrder(BrokerOrderRequest) [LINE 174]:
  ↓
  ZerodhaAdapter.placeOrder()
  ↓
  Kite API call (FINAL BROKER AUTHORITY)

Handle response [LINE 174-198]:
  IF broker rejects: transition to FAILED
  IF broker accepts: save brokerOrderId and state = SUBMITTED/ACCEPTED
  IF exception: catch and transition to FAILED

Return order with broker response recorded
```

**4. Position Authority → BrokerPositionTruthService**
```
Position state is NEVER directly updated by order outcome.

Instead, positions are continuously reconciled:

File: stokr-execution/BrokerPositionTruthService.java:116
  syncUser(userId)
    ├─ Fetch broker positions via Kite API [LINE 127]
    ├─ Fetch OMS executions [LINE 143-149]
    ├─ Compute net quantity by symbol
    ├─ Compare broker qty vs OMS qty
    └─ Detect mismatches [LINE 151-197]

Position is considered CLOSED when:
  - BrokerPositionTruthService.syncUser() detects brokerQty == 0
  - NOT when an order transitions to FILLED
  - Broker is source of truth [LINE 48: "Broker is source of truth"]
```

#### Answer: Can PositionExitOrchestratorService close a position?
❌ **NO** - It creates an exit order but has NO authority over position state.
- Order is placed via OMS
- Position state is determined by BrokerPositionTruthService during sync
- Broker is source of truth

---

### PATH C: MANUAL EXIT (TraderTerminalControlService) - **COMPLIANT**

#### Starting Points
```
TraderTerminalControlService.execute(userId, confirmationToken)
  ├─ Action: "FLATTEN_POSITIONS"
  ├─ Action: "EXIT_ALL"
  ├─ Action: "EMERGENCY_KILL_SWITCH"
```

#### Execution Flow (Line 141-149)
```java
case "EXIT_ALL", "FLATTEN_POSITIONS" -> {
    changedOrders += cancelPendingOrders(userId, notes);
    changedOrders += flattenOpenPositions(userId, notes, flattenResults);
```

#### flattenOpenPositions Implementation (Line 359-410)

**Step 1: Broker Truth Sync**
```
brokerPositionTruthService.syncUser(userId)      // Fetch broker state
BrokerPositionTruthSnapshot snap = snapshot(userId)
```

**Step 2: Collect Targets**
```
IF broker connected:
  Use broker positions (PRIMARY)
ELSE:
  Use PortfolioPosition table
ELSE:
  Use orphan reconciliation records
```

**Step 3: Create Exit Orders**
```java
OmsOrder o = orderPlacementService.place(userId, new CreateOrderRequest(
    target.symbol(),
    side,           // "SELL" or "BUY"
    "MARKET",
    qty.abs(),
    null,
    exitMode,       // LIVE vs PAPER (respects user preference)
    exitBroker,     // "ZERODHA" vs "SIM"
    "TERMINAL_FLATTEN",
    "terminal:flatten:" + userId + ":" + symbol + ":" + timestamp,
    null, null, null, null,
    true,           // exitOrder = true
    "EXIT_SAFE",    // guardMode
    false, null
));
```

**Identical to Path B from this point** ✅

#### Answer: Can TraderTerminalControlService close a position?
❌ **NO** - Same as Path B. It creates orders but position state is determined by BrokerPositionTruthService.

---

### MARKET-CLOSE FLATTEN (OmsSafetyScheduler) - **COMPLIANT**

#### Trigger (Line 21-28)
```
@Scheduled(cron = "${stokr.oms.market-close.flatten-cron:0 20 15 * * MON-FRI}")
public void nseMarketCloseFlatten() {
    triggerFlattenIfDue("NSE");
}

@Scheduled(cron = "${stokr.oms.market-close.mcx-flatten-cron:0 50 23 * * MON-FRI}")
public void mcxMarketCloseFlatten() {
    triggerFlattenIfDue("MCX");
}
```

#### Execution (Line 51-70)
```java
private void triggerFlattenIfDue(String segment) {
    boolean due = marketCloseProtectionService.shouldFlatten(now, symbol, strategy);
    
    if (!due) return;
    
    killSwitchService.activate(
        MARKET_CLOSE,
        "Scheduled " + segment + " market-close flatten",
        forceCloseStalePositionsEnabled,
        "scheduler"
    );
}
```

**Effect:** Kill switch blocks new orders and triggers automatic flatten via TradingKillSwitchService.

**Follows Path B/C through OrderPlacementService** ✅

---

## AUTHORITY HIERARCHY

### **Correct Authority Chain (Paths B & C)**

```
┌─────────────────────────────────────────────────┐
│ Decision Maker                                   │
│ (PositionExitOrchestratorService or Scheduler)  │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ OrderPlacementService                            │
│ (Guard checks, Risk checks, Idempotency)        │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ RabbitMQ Queue (EXECUTION)                       │
│ (Asynchronous, Durable)                         │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ ExecutionConsumer / ExecutionSimulator           │
│ (Broker submission gateway)                     │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ OrderLifecycleService.submitToBroker()           │
│ (State machine enforcement)                     │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ BrokerAdapter.placeOrder()                       │
│ (Broker vendor neutral)                         │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ ZERODHA KITE API                                 │
│ 🔴 FINAL EXECUTION AUTHORITY                    │
└─────────────────────────────────────────────────┘
```

### **Position State Authority**

```
┌──────────────────────────────────────┐
│ BrokerPositionTruthService.syncUser() │
│ Runs continuously                      │
├──────────────────────────────────────┤
│ 1. Fetch broker positions from Kite  │
│ 2. Fetch OMS executions              │
│ 3. Compute reconciliation             │
│ 4. Detect closed positions           │
│ 5. Block new orders if mismatch      │
└────────────┬─────────────────────────┘
             │
             ↓
   🔴 BROKER TRUTH
   (Source of truth for qty = 0 means CLOSED)
```

---

## CRITICAL FINDINGS

### **Finding 1: HybridExitService Bypasses OMS Entirely**

| Component | Path B/C (Compliant) | Path A (HybridExit) |
|-----------|-------------------|-------------------|
| Goes through OrderPlacementService? | ✅ YES | ❌ NO |
| Persists order to OMS? | ✅ YES | ❌ NO |
| Uses RabbitMQ queue? | ✅ YES | ❌ NO |
| Passes ExecutionGuards? | ✅ YES | ❌ NO |
| Passes RiskEngine? | ✅ YES | ❌ NO |
| Waits for broker confirmation? | ✅ YES (transitions to SUBMITTED/ACCEPTED) | ❌ NO |
| Marks position CLOSED before broker confirms? | ❌ NO | ✅ YES (LINE 393) |
| Catches exceptions on broker call? | ❌ NO (logged, order marked FAILED) | ✅ YES, but position already CLOSED (LINE 400-402) |

### **Finding 2: HybridExitService Has Unilateral Authority**

**Line 393:** `position.setStatus("CLOSED")`
- ✅ Executed if zerodhaAPI.placeMarketOrder() succeeds
- ✅ Executed if zerodhaAPI.placeMarketOrder() throws exception (caught at line 400-402)
- ❌ NO validation against BrokerPositionTruthService
- ❌ NO check for reconciliation mismatches
- ❌ NO state machine enforcement

**Consequence:** A position can be marked CLOSED without:
1. The broker order being successfully placed
2. The broker confirming execution
3. The OMS knowing the order exists
4. BrokerPositionTruthService being aware

### **Finding 3: Can an EXIT Decision Occur Without Broker Execution?**

**Answer: ✅ YES**

In HybridExitService:
```java
private void executeExit(Position position, HybridExitDecision decision) {
    try {
        zerodhaAPI.placeMarketOrder(...);  // LINE 384
        position.setStatus("CLOSED");      // LINE 393 - Executes regardless
        positionRepository.save(position);  // LINE 396
    } catch (Exception e) {
        logger.error("FAILED TO EXECUTE EXIT: {}", e.getMessage());
        // Position was NOT reverted to OPEN
        // Position is still marked CLOSED in database
    }
}
```

**Scenario:**
1. Decision made: `finalAction = "EXIT"`
2. ExitSignal created in database ✅
3. zerodhaAPI.placeMarketOrder() called
4. Network timeout / Zerodha API error
5. Exception caught (line 400-402)
6. Position.status was already set to "CLOSED" (line 393)
7. **Result:** Position marked CLOSED in OMS, but broker has no order

### **Finding 4: Can a Broker Exit Occur Without EXIT Decision?**

**Answer: ✅ YES**

**Scenario:**
1. Trader manually closes position at broker (via Kite web)
2. HybridExitService has NOT called executeExit()
3. Position is still in OMS with status = "OPEN"
4. Next broker sync (BrokerPositionTruthService.syncUser()):
   - Detects brokerQty = 0
   - Detects internalQty > 0
   - Creates GHOST_INTERNAL_POSITION mismatch (LINE 163)
   - Calls handleExternalBrokerExit() (LINE 167)

**Result:** Position closes at broker without an OMS EXIT decision.

This is **CORRECT AND INTENTIONAL** - Broker is source of truth.
But HybridExitService doesn't account for this model.

---

## ORDER PLACEMENT AUTHORITY

### **Question: Does OrderPlacementService Immediately Execute or Only Queue?**

**Answer: OrderPlacementService ONLY QUEUES**

```java
// OrderPlacementService.place() [LINE 49-193]

// Step 1: Create OMS order and persist
OmsOrder order = orderLifecycleService.createOrGetIdempotent(userId, req.idempotencyKey(), draft);

// Step 2: Perform checks and state transitions
order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);
RiskDecision decision = riskEngineService.evaluate(ctx);
if (!decision.allowed()) {
    return orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
}

// Step 3: Queue for asynchronous execution
order = orderLifecycleService.transition(order.getId(), OrderState.PENDING_SUBMISSION, null);
executionService.dispatch(
    new ExecutionDispatchMessage(...),
    false  // asynchronous
);

// Step 4: Return queued order
return orderLifecycleService.getRequired(order.getId());  // Still in PENDING_SUBMISSION
```

**Flow:**
- ✅ Order created and persisted
- ✅ Guards and risk checks executed
- ✅ Order transitioned to PENDING_SUBMISSION
- ✅ Order dispatched to RabbitMQ
- ❌ Order NOT submitted to broker yet
- ❌ Order NOT filled yet

Broker submission happens later in ExecutionSimulator.process().

---

## BROKER AUTHORIZATION SUMMARY

| Authority | Component | Can Accept/Reject | Can Modify | Can Execute | Final Say |
|-----------|-----------|-------------------|-----------|------------|-----------|
| **Execution** | BrokerAdapter (ZerodhaAdapter) | ✅ YES | ❌ NO | ✅ YES (calls Kite) | ✅ YES |
| **Order State** | OrderLifecycleService | ✅ YES (state machine) | ✅ YES | ❌ NO (queued) | ❌ NO |
| **Position State** | BrokerPositionTruthService | ❌ NO (read-only) | ❌ NO | ❌ NO | ✅ YES (sync-based) |
| **Exit Decision** | HybridExitService | ✅ YES (PROBLEMATIC) | ✅ YES (PROBLEMATIC) | ✅ YES (PROBLEMATIC) | ❌ NO (not source of truth) |

---

## ANSWERS TO VALIDATION QUESTIONS

### **Q1: Can HybridExitService directly close a live position?**
✅ **YES**
- Calls `position.setStatus("CLOSED")` at line 393
- Calls `positionRepository.save(position)` at line 396
- No broker confirmation required
- No OMS order required
- **Problem:** Position marked closed before broker confirms

### **Q2: Does OrderPlacementService immediately execute or only queue?**
✅ **ONLY QUEUE**
- Creates order and transitions to PENDING_SUBMISSION
- Dispatches to RabbitMQ (asynchronous)
- Returns order in PENDING_SUBMISSION state
- Broker submission happens asynchronously in ExecutionSimulator
- **Correct behavior**

### **Q3: What component has final authority over execution?**
✅ **BrokerAdapter (specifically ZerodhaAdapter)**
- Calls `adapter.placeOrder(BrokerOrderRequest)` in OrderLifecycleService (line 174)
- Adapter is the only component that touches the Kite API
- Adapter response determines if order is SUBMITTED or FAILED
- **Exception:** HybridExitService also calls zerodhaAPI directly (ANOMALY)

### **Q4: What component has final authority over position state?**
✅ **BrokerPositionTruthService**
- Syncs with broker every time `syncUser()` is called
- Broker qty = 0 means position is CLOSED (regardless of OMS state)
- Detects external exits and halts strategy
- **Exception:** HybridExitService claims direct authority (ANOMALY)

### **Q5: Can an EXIT decision occur without an actual broker exit?**
✅ **YES** (in HybridExitService only)
- Decision → zerodhaAPI.placeMarketOrder() throws exception
- Position.setStatus("CLOSED") already executed
- Broker has no order
- **Not possible in Paths B/C** (order tracked in OMS)

### **Q6: Can a broker exit occur without an EXIT decision?**
✅ **YES** (intentional and correct)
- Trader closes position externally at broker
- BrokerPositionTruthService.syncUser() detects it
- Calls handleExternalBrokerExit() → suppresses auto-exits → halts strategy
- **Correct:** Broker is source of truth

---

## COMPLIANCE STATUS

| Path | Name | Entry Point | Queue | OMS Tracking | Guard Checks | Risk Checks | Broker Authority | Position Authority | Status |
|------|------|-------------|-------|-------------|--------------|------------|-----------------|------------------|--------|
| A | Hybrid | HybridExitService | ❌ None | ❌ None | ❌ None | ❌ None | 🔴 Bypassed | 🔴 Unilateral | 🔴 CRITICAL |
| B | Orchestrator | PositionExitOrchestratorService | ✅ RabbitMQ | ✅ Full | ✅ EXIT_SAFE | ✅ Full | ✅ Correct | ✅ Correct | ✅ COMPLIANT |
| C | Terminal | TraderTerminalControlService | ✅ RabbitMQ | ✅ Full | ✅ EXIT_SAFE | ✅ Full | ✅ Correct | ✅ Correct | ✅ COMPLIANT |

---

## CONCLUSION

### ✅ PATHS B & C ARE CORRECT
- Orders go through proper OMS workflow
- Guards and risk checks are enforced
- Broker has final authority over execution
- BrokerPositionTruthService has final authority over position state
- External broker exits are detected and handled
- Strategy runtime is halted on external closes

### 🔴 PATH A (HybridExitService) IS FUNDAMENTALLY BROKEN
- **Violates OMS architecture**
- **Marks positions closed before broker confirms**
- **Cannot handle broker failures gracefully**
- **Bypasses all safety checks (guards, risk engine)**
- **Conflicts with BrokerPositionTruthService authority model**
- **Creates data inconsistency if broker rejects or doesn't receive order**

### 🔴 CRITICAL ISSUE
The decision to "EXIT" is made by HybridExitService, but:
1. The ORDER is placed outside OMS (no idempotency key, no state tracking)
2. The POSITION is marked CLOSED unilaterally (before broker confirms)
3. The BROKER has no knowledge of OMS decisions (separate execution path)
4. The TRUTH (broker state) is fetched separately by BrokerPositionTruthService

This creates a **three-tier authority problem** where no single component can verify consistency.

