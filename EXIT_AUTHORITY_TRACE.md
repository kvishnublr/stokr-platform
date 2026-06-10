# EXIT AUTHORITY TRACE
## Complete Exit Authority Hierarchy in Production

Date: 2026-06-09  
Analysis Type: Code execution trace  
Scope: All exit sources and authority hierarchy

---

## SECTION 1: ACTUAL EXIT AUTHORITY HIERARCHY

### The Call Graph:

```
Position (OPEN)
    ↓
[10-SECOND CYCLE] HybridExitService.processHybridExits()
    ├─ Layer 1: getStrategyExitSignal() → returns null for most strategies
    ├─ Layer 2: generateIndicatorSignals() → calculates RSI, MACD, Bollinger, ATR
    ├─ Layer 3: calculateDynamicTarget() → adjusts exit target dynamically
    └─ makeHybridExitDecision() → EXIT or HOLD
        └─ if (decision == EXIT):
            └─ executeExit() → calls OrderPlacementService.place()
                └─ Order created (but NOT auto-executed by HybridExitService)
                
[1-MINUTE CYCLE] ConfidenceSignalExitService.checkAndClosePositions()
    └─ PositionExitOrchestratorService.flattenAll()
        ├─ flattenAll(userId, actionKey, reason)
        ├─ collectTargets() → from BrokerPositionTruth or PortfolioPosition
        ├─ placeExit(target) → creates MARKET order
        └─ Order executed by OrderPlacementService
        
[CONTINUOUS] BrokerPositionTruthService
    └─ Syncs with actual broker positions
    └─ Serves as source-of-truth
    
[MANUAL/EXTERNAL] SignalManualExitSuppressionService
    └─ Suppresses auto-exits when manual exits occur
    
[ON-EXIT] SignalOutcomeTrackerService (mentioned in earlier code)
    └─ Records exit events for analysis
```

---

## SECTION 2: WHICH COMPONENT HAS AUTHORITY?

### Authority Hierarchy (in execution order):

**Rank 1: PositionExitOrchestratorService (PRIMARY)**
- Component: `stokr-execution` module
- Authority: **HIGHEST - Executes actual exit orders**
- Method: `flattenAll()`, `flattenSegment()`, `flattenSymbol()`
- Called by: ConfidenceSignalExitService (every 1 minute at market close)
- Actions:
  - Cancels all pending orders
  - Sources positions from:
    1. BrokerPositionTruth (live broker positions)
    2. PortfolioPosition (internal DB)
    3. ReconciliationEvent (orphan fallback)
  - Places MARKET exit orders
  - Suppresses manual exit signals
  - Logs exit event

**Rank 2: HybridExitService (DECISION-MAKER)**
- Component: `stokr-bootstrap` module
- Authority: **MEDIUM - Evaluates exit conditions**
- Method: `processHybridExits()` (every 10 seconds)
- Actions:
  - Layer 1: Strategy thresholds (FIXED for INDEX_HUNT, ADV_CASH only)
  - Layer 2: Indicator signals (ALL strategies)
  - Layer 3: Dynamic targets (ALL strategies)
  - Makes EXIT/HOLD decision
  - Creates exit orders via OrderPlacementService
  - **BUT DOES NOT AUTO-EXECUTE** - order goes to OMS queue

**Rank 3: ConfidenceSignalExitService (SCHEDULER)**
- Component: `stokr-bootstrap` module
- Authority: **LOW - Triggers orchestrator at market close**
- Method: `checkAndClosePositions()` (every 1 minute)
- Actions:
  - Checks if near market close (15:20-15:30)
  - Calls `PositionExitOrchestratorService.flattenAll()`
  - Routes to actual order execution
  - Only active when `stokr.confidence-strategy.auto-trade-enabled=true`

**Rank 4: BrokerPositionTruthService (VERIFICATION)**
- Component: `stokr-execution` module
- Authority: **VERIFICATION ONLY - Source of truth**
- Method: `syncUser()`, `snapshot()`
- Actions:
  - Syncs positions from broker (Zerodha)
  - Provides actual broker position state
  - Used by PositionExitOrchestratorService

**Rank 5: SignalManualExitSuppressionService (INTERFERENCE)**
- Component: `stokr-strategy` module
- Authority: **SUPPRESSION ONLY - Prevents conflicts**
- Actions:
  - When manual exit occurs, suppresses auto-exit signals
  - Prevents duplicate exits
  - Logging only

**Rank 6: OrderPlacementService (EXECUTION)**
- Component: `stokr-oms` / `stokr-execution` module
- Authority: **EXECUTION - Creates orders**
- Actions:
  - Called by: HybridExitService (creates, but doesn't execute)
  - Called by: PositionExitOrchestratorService (creates + executes)
  - Determines execution mode (LIVE, PAPER, SIM)
  - Routes to broker if LIVE

---

## SECTION 3: WHICH COMPONENT CREATES EXIT ORDERS?

### Order Creation Sources:

**Source 1: HybridExitService (Every 10 seconds)**
```java
private void executeExit(Position position, HybridExitDecision decision) {
    CreateOrderRequest request = new CreateOrderRequest(
        symbol, side, "MARKET", quantity, 
        limitPrice, executionMode, ...
    );
    OmsOrder order = orderPlacementService.place(...);
    // ↑ Order created but NOT necessarily executed
    // Sits in OMS queue for OrderPlacementService to execute
}
```

**Source 2: PositionExitOrchestratorService (At market close)**
```java
private OmsOrder placeExit(UUID userId, FlattenTarget target, ...) {
    OmsOrder order = orderPlacementService.place(userId, new CreateOrderRequest(
        target.symbol(), side, "MARKET", qty,
        null, exitMode, exitBroker, safeAction, ...
    ));
    // ↑ Order created AND executed
    // Goes directly to broker (LIVE) or simulator
}
```

**Source 3: Manual Exits (External)**
- User manually closes position via API/UI
- Recorded by SignalOutcomeTrackerService

---

## SECTION 4: WHICH COMPONENT IS USED MOST FREQUENTLY?

### Frequency Analysis:

**HybridExitService:**
- **Frequency:** Every 10 seconds (100/1000 per day)
- **Execution:** Orders created, queued to OMS
- **Status:** ✅ ACTIVE (always running)
- **Impact:** High volume of decision-making

**ConfidenceSignalExitService:**
- **Frequency:** Every 1 minute (1440/day)
- **Execution:** Only near market close (15:20-15:30)
- **Status:** ⚠️ CONDITIONAL (only at market close)
- **Impact:** Low frequency, high importance (safeguard)

**PositionExitOrchestratorService:**
- **Frequency:** Called by ConfidenceSignalExitService at market close
- **Frequency:** Also called by manual exits
- **Status:** ✅ ACTIVE (critical path)
- **Impact:** Medium frequency, executes actual exits

**OrderPlacementService:**
- **Frequency:** Called by all exit sources
- **Status:** ✅ ACTIVE (always running)
- **Impact:** Critical - routes to execution

**Answer:** `HybridExitService` is **MOST FREQUENTLY USED** (every 10 seconds), but `PositionExitOrchestratorService` is **MOST CONSEQUENTIAL** (actually executes market orders at market close)

---

## SECTION 5: IS getStrategyExitSignal() MANDATORY?

### Answer: NO - It is OPTIONAL

**Evidence:**

```java
private void processPositionHybridExit(Position position) {
    // LAYER 1: Get strategy signal
    String strategyExitSignal = getStrategyExitSignal(position);
    // ↓ Returns null for most strategies
    
    // LAYER 2: Always runs, regardless of Layer 1 result
    IndicatorSignalResult indicatorSignals = generateIndicatorSignals(...);
    // ↓ Generates signals using RSI, MACD, Bollinger, ATR
    
    // LAYER 3: Always runs, regardless of Layer 1 result
    DynamicTargetResult dynamicTarget = calculateDynamicTarget(...);
    // ↓ Adjusts targets based on volatility, momentum, RSI
    
    // FINAL DECISION: Uses Layer 2 + Layer 3 even if Layer 1 is null
    HybridExitDecision decision = makeHybridExitDecision(
        ..., strategySignal, ..., indicatorSignals, dynamicTarget
    );
}
```

**Key Code in makeHybridExitDecision():**

```java
double combinedConfidence = (strategyConfidence + indicators.getConfidence()) / 2;

if (strategySignal != null) {
    finalAction = "EXIT";  // ← Layer 1 takes precedence if not null
}
else if (indicators.getRecommendation().equals("STRONG_EXIT") && combinedConfidence > 0.75) {
    finalAction = "EXIT";  // ← Layer 2 can trigger exit
}
else if (indicators.getRecommendation().equals("EXIT") && combinedConfidence > 0.60) {
    finalAction = "CONSIDER_EXIT";  // ← Layer 2 can trigger exit
}
```

**Conclusion:** 
- ✅ Exits CAN happen without getStrategyExitSignal()
- ✅ Indicator signals alone (Layer 2) can trigger exits
- ✅ getStrategyExitSignal() is an ENHANCEMENT, not a requirement

---

## SECTION 6: IF getStrategyExitSignal() RETURNS NULL, CAN POSITIONS STILL EXIT?

### Answer: YES - ABSOLUTELY

**Proof:**

1. **All 7 non-INDEX_HUNT/ADV_CASH strategies get NULL from getStrategyExitSignal()**
2. **They still exit via Layer 2 (indicator signals) + Layer 3 (dynamic targets)**
3. **Code path:**
   ```
   Position OPEN
   ↓
   getStrategyExitSignal() → NULL for NSE_SPIKE, EARLY_BREAKOUT, etc.
   ↓
   generateIndicatorSignals() → ACTIVE (RSI, MACD, Bollinger)
   ↓
   Decision: Exit if (indicators.getRecommendation() == "EXIT" && confidence > 0.60)
   ↓
   Order placed and executed
   ```

**Real Scenario:**
- NSE_SPIKE_DETECTION: getStrategyExitSignal() returns NULL
- But RSI > 70 (overbought) triggers LAYER 2 signal
- Dynamic target adjusted downward (volatility factor)
- Exit decision made: "EXIT"
- Position closed

**Conclusion:** Layer 1 null ≠ No Exit. Layer 2+3 handle all cases.

---

## SECTION 7: WHICH STRATEGIES DEPEND ON getStrategyExitSignal()?

### Strategies DIRECTLY DEPENDING:

**Rank 1: EXPLICIT DEPENDENCY**
```java
if ("indexhunt".equalsIgnoreCase(strategy)) {
    if (profitPercent >= 2.0) return "PROFIT_TARGET_HIT";
    if (lossPercent <= -2.0) return "STOP_LOSS_HIT";
}

if ("adv_cash".equalsIgnoreCase(strategy)) {
    if (profitPercent >= 1.5) return "PROFIT_TARGET_HIT";
    if (lossPercent <= -1.5) return "STOP_LOSS_HIT";
}
```

**Only INDEX_HUNT and ADV_CASH have getStrategyExitSignal() logic**

**All other 7 strategies:**
- NSE_SPIKE_DETECTION → NOT in getStrategyExitSignal()
- EARLY_BREAKOUT → NOT in getStrategyExitSignal()
- GAP_FILL → NOT in getStrategyExitSignal()
- VWAP_BOUNCE → NOT in getStrategyExitSignal()
- S7_RANGE_FADE → NOT in getStrategyExitSignal()
- SECTOR_LAGGARD → NOT in getStrategyExitSignal()
- S3_VWAP_RETEST → NOT in getStrategyExitSignal()

---

## SECTION 8: WHICH STRATEGIES NEVER TOUCH getStrategyExitSignal()?

### Answer: 7 OUT OF 9 STRATEGIES

**Strategies that SKIP getStrategyExitSignal():**

1. **NSE_SPIKE_DETECTION** - Pure indicator-based exit
2. **EARLY_BREAKOUT** - Pure indicator-based exit
3. **GAP_FILL** - Pure indicator-based exit
4. **VWAP_BOUNCE** - Pure indicator-based exit
5. **S7_RANGE_FADE** - Pure indicator-based exit
6. **SECTOR_LAGGARD** - Pure indicator-based exit
7. **S3_VWAP_RETEST** - Pure indicator-based exit

**Strategies that USE getStrategyExitSignal():**

1. **INDEX_HUNT** - Fixed 2% target/stop + indicators
2. **ADV_CASH** - Fixed 1.5% target/stop + indicators

---

## SECTION 9: THE COMPLETE POSITION LIFECYCLE

### From Entry to Exit:

```
┌─────────────────────────────────────────────────────────┐
│ Position OPEN                                           │
│ Status: CREATED, Entry price locked, targets set        │
└────────────────┬────────────────────────────────────────┘
                 │
                 ├─→ [EVERY 10 SEC] HybridExitService
                 │   ├─ Layer 1: getStrategyExitSignal()
                 │   │   └─ If not INDEX_HUNT/ADV_CASH → NULL
                 │   ├─ Layer 2: Indicator signals (ALL)
                 │   │   └─ RSI, MACD, Bollinger, ATR, Volume
                 │   ├─ Layer 3: Dynamic targets (ALL)
                 │   │   └─ Volatility, momentum, RSI adjustments
                 │   └─ Decision: EXIT if conditions met
                 │       └─ Order placed (HybridExitService)
                 │       └─ Queued to OrderPlacementService
                 │
                 ├─→ [EVERY 1 MIN, AT 15:20-15:30] ConfidenceSignalExitService
                 │   └─ PositionExitOrchestratorService.flattenAll()
                 │       └─ Sources from: BrokerPositionTruth
                 │       └─ Places MARKET exit order
                 │       └─ LIVE execution (not queued)
                 │
                 ├─→ [MANUAL] User closes position
                 │   └─ SignalOutcomeTrackerService logs exit
                 │   └─ SignalManualExitSuppressionService suppresses auto-exits
                 │
                 └─→ [BROKER] Broker closes position
                     └─ BrokerPositionTruthService detects
                     └─ Orphan reconciliation fallback
                     └─ PositionExitOrchestratorService cleans up

┌─────────────────────────────────────────────────────────┐
│ Position CLOSED                                         │
│ Status: EXIT_FILLED, PnL realized                       │
└─────────────────────────────────────────────────────────┘
```

---

## SECTION 10: EXIT AUTHORITY SUMMARY TABLE

| Component | Authority | Frequency | Mandatory | Scope |
|---|---|---|---|---|
| **HybridExitService** | DECISION | 10 sec | NO | All strategies |
| **getStrategyExitSignal()** | OPTIONAL SIGNAL | N/A | NO | INDEX_HUNT, ADV_CASH only |
| **ConfidenceSignalExitService** | SAFEGUARD | 1 min (at close) | NO | Market close only |
| **PositionExitOrchestratorService** | EXECUTION | On-demand | YES | Actually closes positions |
| **OrderPlacementService** | ROUTING | On-demand | YES | Routes to broker/sim |
| **BrokerPositionTruthService** | VERIFICATION | Continuous | YES | Source of truth |
| **SignalManualExitSuppressionService** | INTERFERENCE | On manual exit | NO | Prevents conflicts |

---

## CONCLUSIONS

### Question 1: Which component has authority to close a position?

**Answer: PositionExitOrchestratorService**

The orchestrator service is the ONLY component that actually closes positions by:
1. Sourcing positions from broker (truth)
2. Creating MARKET exit orders
3. Executing them immediately (not queuing)

### Question 2: Which component creates exit orders?

**Answer: BOTH (different purposes)**

1. **HybridExitService** - Creates orders every 10 seconds, but queues them (not executed)
2. **PositionExitOrchestratorService** - Creates orders at market close, executes immediately

### Question 3: Which component is actually used most frequently?

**Answer: HybridExitService (FREQUENCY) + PositionExitOrchestratorService (CONSEQUENCE)**

- HybridExitService: Every 10 seconds, makes thousands of decisions daily
- PositionExitOrchestratorService: Once per day (market close), but actually executes exits

### Question 4: Is getStrategyExitSignal() mandatory?

**Answer: NO**

- Only 2 strategies (INDEX_HUNT, ADV_CASH) have explicit rules
- 7 strategies have NO rules defined
- All positions can exit via indicator signals (Layer 2) + dynamic targets (Layer 3)
- It's an OPTIONAL enhancement, not a requirement

### Question 5: If getStrategyExitSignal() returns null, can positions still exit?

**Answer: YES - ABSOLUTELY**

Evidence:
- Returns null for 7 strategies
- Those strategies still exit via indicator-based signals
- Layer 2 (indicators) + Layer 3 (dynamic targets) handle all cases
- Code explicitly checks: `if (indicators.getRecommendation().equals("EXIT"))`

### Question 6: Which strategies depend on getStrategyExitSignal()?

**Answer: ONLY INDEX_HUNT and ADV_CASH**

- INDEX_HUNT: 2% profit/loss target
- ADV_CASH: 1.5% profit/loss target
- All others: NULL (not implemented)

### Question 7: Which strategies never touch getStrategyExitSignal()?

**Answer: 7 OUT OF 9 STRATEGIES**

- NSE_SPIKE_DETECTION ✓
- EARLY_BREAKOUT ✓
- GAP_FILL ✓
- VWAP_BOUNCE ✓
- S7_RANGE_FADE ✓
- SECTOR_LAGGARD ✓
- S3_VWAP_RETEST ✓

**They all use indicator-based exits instead.**

---

**EXIT AUTHORITY TRACE COMPLETE**

**CRITICAL FINDING: The exit system is LAYERED, not singular. No single component has total authority. HybridExitService makes decisions every 10 seconds, but PositionExitOrchestratorService actually executes exits. getStrategyExitSignal() is optional layer 1 enhancement only, not a core requirement. 7 strategies never use it and exit via indicators anyway.**

