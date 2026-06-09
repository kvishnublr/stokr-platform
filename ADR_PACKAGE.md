# ARCHITECTURE DECISION RECORD PACKAGE
## Final Architecture Lock - Before Implementation

**Document ID:** ADR-PACKAGE-P0  
**Date:** June 9, 2026  
**Status:** PENDING APPROVAL  
**Applies To:** Phase 1 P0 Implementation  

---

## ADR-001: Exits Are Separate From Entry Strategy System

### Decision

Automatic exits will be implemented in a separate `PositionMonitoringService`, NOT integrated into strategy generators.

### Rationale

**Current State:**
- Strategy generators (IndexHunt, ADV_CASH, etc.) generate BUY/SELL signals
- They are designed to find entry opportunities
- They are NOT designed to monitor existing positions

**Why Separate:**
1. **Single Responsibility:** Strategies focus on entry, not exit
2. **Operational Independence:** Can enable/disable exits without affecting entries
3. **Rollback Safety:** Can roll back exits without affecting strategy engine
4. **Evolutionary:** Can evolve exit logic independently from strategy logic
5. **Testing:** Exit logic can be tested without strategy evaluation

**Alternative Rejected:**
❌ Modify entry strategies to generate EXIT signals
- Would couple exit logic to strategy logic
- Hard to roll back
- Hard to test independently
- Hard to disable one without affecting other

### Implementation

```
Entry Flow:
  Strategy → BUY/SELL signal → OrderPlacementService → Entry order

Exit Flow (Separate):
  PositionMonitoringService → EXIT decision → OrderPlacementService → Exit order
```

### Dependencies Affected
- StrategyEvaluationScheduler: UNCHANGED
- Strategy generators: UNCHANGED
- OrderPlacementService: REUSED (both use it)
- PortfolioAccountingService: REUSED (both use it)

### Rollback Impact
- LOW: Can disable PositionMonitoringService without affecting entry system

---

## ADR-002: Single Automatic Exit Orchestrator

### Decision

`PositionMonitoringService` will be the ONLY automatic exit orchestrator in P0.

No other components create exit decisions or exit orders.

### Rationale

**Simplicity:**
- One source of truth for exit decisions
- Easy to reason about
- Easy to debug
- Easy to test

**Control:**
- One feature flag enables/disables all exits
- Kill switch is simple
- Monitoring is focused

**Safety:**
- Duplicate prevention is centralized
- Stale price validation is centralized
- Audit logging is centralized

**Operational:**
- One scheduler to monitor
- One log stream to watch
- One rollback path

**Alternative Rejected:**
❌ Multiple exit orchestrators (e.g., PositionMonitoringService + RiskManager)
- Would require synchronization
- Would complicate duplicate prevention
- Would require coordination logic
- Would be harder to debug

### Implementation

```
PositionMonitoringService {
  - Load positions
  - Validate prices
  - Evaluate targets/stops
  - Create exit decisions
  - Create exit orders
}

ONLY source of automatic exits in P0.
```

### Rollback Impact
- TRIVIAL: One feature flag disables all

---

## ADR-003: Reuse Existing OMS Infrastructure

### Decision

P0 will NOT create any new order execution infrastructure.

Will reuse:
- `OrderPlacementService` (existing)
- `OrderLifecycleService` (existing)
- `PortfolioAccountingService` (existing)
- `MarketDataQueryService` (existing)
- Order/execution repository layer (existing)

### Rationale

**Proven:**
- Entry system uses these successfully
- 7 positions already entered = infrastructure works
- No need to build parallel system

**Risk Reduction:**
- Don't introduce new untested infrastructure
- Don't risk regression in execution pipeline
- Leverage existing safety checks (risk engine, validation)

**Code Reuse:**
- ~0 new OMS code needed
- Only decision and evaluation logic needed
- Minimal surface area for bugs

**Testing:**
- Order creation already tested for entries
- Can test exit orders same way
- Infrastructure tests don't need to be rewritten

**Alternative Rejected:**
❌ Create custom exit order handler
- Would duplicate order creation logic
- Would bypass existing safety checks
- Would require parallel testing

### Implementation

```
Exit Order Creation Flow:
  ExitOrderCreationService (NEW)
    ├─ Check duplicates
    ├─ Build CreateOrderRequest
    └─ Call OrderPlacementService.place() (EXISTING)
        ├─ Validate
        ├─ Risk check
        ├─ Transition states
        └─ Dispatch to broker
```

### Rollback Impact
- MINIMAL: Exit service is just a thin wrapper

---

## ADR-004: P0 Supports Only TARGET_HIT and STOP_LOSS_HIT

### Decision

P0 exit types are strictly limited to:
1. `TARGET_HIT` - current price >= target
2. `STOP_LOSS_HIT` - current price <= stop loss

No other exit types in P0.

### Rationale

**Minimum Viable:**
- These two types solve the core problem
- Positions stuck due to no monitoring = solved
- 7 positions can exit = solved

**Future Extensibility:**
- Architecture supports adding types later
- ExitReason enum is extensible
- PositionMonitoringService has hook points

**Avoids Scope Creep:**
- Prevents: time-based exits, profit/loss % exits, AI exits
- Focuses on core: reach the target you set or stop loss

**Testing Simplicity:**
- 2 evaluators = simple
- Each has 1 job
- Easy to test independently

**Alternative Rejected:**
❌ Support 5+ exit types in P0
- Adds complexity
- Adds test burden
- Increases rollback risk
- Dilutes focus

### Implementation

```java
public enum ExitReason {
    TARGET_HIT("Position hit profit target"),
    STOP_LOSS_HIT("Position hit stop loss");
}
```

### Future Extensions (Phase 2+)

```java
// NOT in P0, but structure allows this:
RSI_EXIT("RSI overbought/oversold")
TIME_BASED_EXIT("Position held > N minutes")
VOLATILITY_EXIT("Volatility exceeded limit")
```

---

## ADR-005: Explicit Deferral of All Optimization Features

### Decision

The following are EXPLICITLY OUT OF P0 and deferred to Phase 2+:

- ❌ RSI indicator
- ❌ MACD indicator
- ❌ ATR indicator
- ❌ Bollinger Bands
- ❌ AI/ML optimization
- ❌ Confidence scoring
- ❌ Dynamic target adjustment
- ❌ Hybrid Exit Engine

### Rationale

**Focus:**
- P0 goal: automate exits
- Not goal: optimize exits
- Can optimize AFTER automation works

**Risk:**
- Each optimization adds complexity
- Each optimization adds test burden
- Each optimization adds operational complexity
- Each optimization adds rollback difficulty

**Proof First:**
- Prove basic exits work
- Prove no duplicate issues
- Prove audit trail works
- THEN add optimizations

**Timeline:**
- P0: Get exits working (1 week)
- Phase 2: Add optimizations (2-3 weeks)

**Hybrid Exit Engine:**
- Completely deferred
- Separate project
- Separate code review
- Separate deployment

### Implementation

```
P0 Only:
  Target detection (simple comparison)
  Stop loss detection (simple comparison)
  
Phase 2+:
  Indicators (RSI, MACD, ATR, BB)
  AI/ML
  Dynamic targets
  Confidence scoring
  Hybrid Exit Engine
```

### Dependencies
- No dependencies on deferred features
- PositionMonitoringService doesn't need them
- Evaluators don't need them

---

## ADR-006: Mandatory Dry-Run Validation

### Decision

Before ANY exit order is created in production, must pass through dry-run validation.

Implementation:
```
stokr.position-monitor-exit-orders-enabled = false  (default)

When FALSE:
  - PositionMonitoringService evaluates positions
  - ExitDecisions are created
  - Events are published
  - Audit events are logged
  - BUT no OMS orders created
  - Logs show: "DRY_RUN: Would exit SBIN..."

When TRUE:
  - Everything above PLUS
  - OMS orders actually created
  - Orders sent to broker
  - Execution happens
```

### Rationale

**Safety:**
- Observe behavior before acting
- No risk of false exits
- Can verify logic without market risk

**Validation:**
- Dry-run must be stable for 2-3 trading sessions
- Must detect all targets correctly
- Must detect all stops correctly
- Must show no duplicates

**Operational:**
- Run dry-run in LIVE environment with real data
- Can observe if exits would have happened
- Can verify timing is correct
- Can verify prices are reasonable

**Rollback:**
- If issues found, disable and debug
- No live orders created = no losses
- Can re-run dry-run anytime

### Implementation

```java
// In ExitOrderCreationService

@Value("${stokr.position-monitor-exit-orders-enabled:false}")
private boolean exitOrdersEnabled;

public OmsOrder createExitOrder(ExitDecision decision) {
    if (!exitOrdersEnabled) {
        log.info("DRY_RUN: Would exit {} - {}",
            decision.getSymbol(), decision.getExitReason());
        return null;  // Don't create order
    }
    
    // Create actual order
    return orderPlacementService.place(...);
}
```

### Validation Checklist (must pass before progressing)

```
[ ] 50+ positions evaluated
[ ] All target hits detected correctly
[ ] All stop losses detected correctly
[ ] 0 duplicate evaluations
[ ] 0 false positives
[ ] Audit events complete
[ ] Timing reasonable
[ ] Prices consistent
```

---

## DECISION SUMMARY TABLE

| ADR | Decision | Status | Risk |
|-----|----------|--------|------|
| 001 | Separate exit from entry | LOCKED | LOW |
| 002 | Single orchestrator | LOCKED | LOW |
| 003 | Reuse OMS infrastructure | LOCKED | LOW |
| 004 | Only TARGET/STOP | LOCKED | LOW |
| 005 | Defer optimizations | LOCKED | MEDIUM (scope) |
| 006 | Mandatory dry-run | LOCKED | LOW |

**Overall Status:** APPROVED FOR IMPLEMENTATION

---

## ARCHITECTURE IMPLICATIONS

### What This Means for Implementation

1. **Code Organization:**
   - PositionMonitoringService in stokr-oms
   - Evaluators in stokr-oms
   - No new modules needed
   - No new packages needed

2. **Dependencies:**
   - Don't add new dependencies
   - Use existing: OrderPlacementService, PortfolioPositionRepository, MarketDataQueryService
   - Spring events for publishing

3. **Testing:**
   - Test PositionMonitoringService independently
   - Test Evaluators independently
   - Mock OrderPlacementService for exit tests
   - Real integration tests with full OMS flow

4. **Deployment:**
   - Single feature flag controls all exits
   - Can deploy with exits disabled
   - Can enable dry-run safely
   - Can disable immediately if issues

5. **Monitoring:**
   - Watch logs for "DRY_RUN" messages
   - Watch logs for target/stop detections
   - Watch for duplicate prevention working
   - Watch for errors

---

## CONSTRAINTS & BOUNDARIES

### Hard Constraints (Cannot Change)

- Must use OrderPlacementService (no exceptions)
- Must validate prices not stale (no exceptions)
- Must prevent duplicates (no exceptions)
- Must have dry-run mode (no exceptions)
- Must have kill switch (no exceptions)

### Soft Constraints (Should Respect)

- Should reuse existing infrastructure
- Should minimize schema changes
- Should keep single-threaded (sequential processing)
- Should keep simple (no parallelization in P0)

### Out of Bounds (Explicitly NOT allowed)

- No new indicators
- No AI/ML
- No dynamic targets
- No modifications to strategy engine
- No modifications to entry signal generation

---

## APPROVAL GATES

**Before implementation begins:**

```
[ ] ADR-001 approved by: ____
[ ] ADR-002 approved by: ____
[ ] ADR-003 approved by: ____
[ ] ADR-004 approved by: ____
[ ] ADR-005 approved by: ____
[ ] ADR-006 approved by: ____
[ ] Schema review completed
[ ] Component list finalized
[ ] Dependency map approved
[ ] Implementation sequence approved
[ ] Deployment sequence approved

Approved by: __________________
Date: __________________
```

---

## REVISION HISTORY

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-06-09 | Initial ADR package |

