# PHASE 1: PRODUCTION-GRADE EXIT EXECUTION FRAMEWORK
## Automatic Position Monitoring and Exit Orchestration

**Version:** 1.0  
**Date:** June 9, 2026  
**Status:** Design Only - No Implementation Yet  
**Scope:** Target Hit + Stop Loss Exit Only  

---

## EXECUTIVE SUMMARY

**Problem:** 7 open positions, 0 exits executed. Target/stop prices stored but never checked.

**Solution:** Automatic position monitoring service that creates exit orders when conditions are met.

**Scope:** Phase 1 only
- Target price hit detection
- Stop loss hit detection
- No indicators, no AI, no optimization

**Architecture Principle:** Decouple exit decision from OMS integration. Create reusable layers so future exit mechanisms plug in cleanly.

---

## 1. ARCHITECTURE OVERVIEW

### 1.1 Execution Flow (Complete)

```
┌─────────────────────────────────────────────────────────────────────┐
│ POSITION MONITORING SERVICE                                         │
│ (Runs every 30 seconds)                                             │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ MARKET DATA VALIDATION LAYER                                        │
│ - Load current prices from MarketData                               │
│ - Check if prices are fresh (< 15 seconds old)                     │
│ - Skip evaluation if stale                                         │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ SESSION VALIDATION LAYER                                            │
│ - Check market hours (pre-market, post-market)                     │
│ - Check holidays                                                   │
│ - Check maintenance windows                                        │
│ - Skip if outside trading hours                                    │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ POSITION EVALUATION LAYER                                           │
│ - Load all OPEN positions per user                                 │
│ - Check: current_price >= target_price?                           │
│ - Check: current_price <= stop_loss_price?                        │
│ - Determine exit reason (TARGET_HIT or STOP_LOSS_HIT)            │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ EXIT DECISION LAYER (Reusable Abstraction)                         │
│                                                                     │
│ ExitDecision {                                                      │
│   position_id                                                       │
│   symbol                                                            │
│   exit_reason                                                       │
│   exit_price                                                        │
│   decision_timestamp                                                │
│   confidence → NOT USED IN PHASE 1                                │
│ }                                                                   │
│                                                                     │
│ Purpose:                                                            │
│ - Pure decision object (no side effects)                           │
│ - Can be tested independently                                      │
│ - Future exit mechanisms use same object                           │
│ - Decouples decision from execution                                │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ DUPLICATE PREVENTION LAYER                                          │
│ - Check if EXIT_PENDING order already exists                       │
│ - Check if EXIT_SUBMITTED order already exists                     │
│ - Check if order created in last 30 seconds                        │
│ - Skip if duplicate detected                                       │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ EXIT EVENT CREATION LAYER                                           │
│                                                                     │
│ ExitEvent {                                                         │
│   timestamp                                                         │
│   position_id                                                       │
│   symbol                                                            │
│   entry_price                                                       │
│   current_price                                                     │
│   exit_reason                                                       │
│   environment (LIVE/PAPER/SIMULATION)                             │
│   user_id                                                           │
│   strategy_name                                                     │
│ }                                                                   │
│                                                                     │
│ Purpose:                                                            │
│ - Immutable record of exit decision                                │
│ - Published as domain event                                        │
│ - Triggers audit trail                                            │
│ - No side effects yet                                              │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ OMS INTEGRATION LAYER                                               │
│ - Create CreateOrderRequest (decoupled from decision)              │
│ - Call OrderPlacementService.place()                               │
│ - Handle OMS-specific failures                                     │
│ - Update position state to EXIT_PENDING                            │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ AUDIT TRAIL LAYER                                                   │
│ - Log exit event to database                                       │
│ - Publish event for listeners (compliance, monitoring, etc.)       │
│ - Store reason, timestamp, prices, environment                     │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ BROKER EXECUTION (OrderPlacementService handles this)              │
│ - OMS transitions order states                                      │
│ - Sends to broker (Zerodha)                                        │
│ - Updates position state to EXIT_SUBMITTED                         │
└─────────────────────────────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────────────────────────────┐
│ POSITION CLOSING (PortfolioAccountingService handles this)         │
│ - Order executes at broker                                         │
│ - Execution recorded in OMS                                        │
│ - PortfolioAccountingService.applyFill() called                   │
│ - Position quantity becomes 0 → CLOSED                            │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Key Design Principle: Layer Independence

**Critical:** Exit Decision is decoupled from OMS integration.

```
┌─────────────────────────────┐
│ ExitDecision                │  Pure decision logic
│ (no side effects)           │  Can be tested in isolation
│ - Check price vs target     │  Easy to debug
│ - Check price vs stop       │  Easy to extend
└─────────────────────────────┘
         ↑  ↓
┌─────────────────────────────────────────┐
│ ExitEvent                               │  Domain event
│ (immutable record)                      │  Published to all listeners
└─────────────────────────────────────────┘
         ↑  ↓
┌─────────────────────────────────────────┐
│ OMS Integration                         │  External system integration
│ (has side effects)                      │  Can fail/retry independently
│ - OrderPlacementService.place()         │  Can be mocked for testing
└─────────────────────────────────────────┘
```

**Future extensibility:** Tomorrow, if we add "RSI-based exit" or "AI-based exit", it:
1. Creates its own ExitDecision
2. Publishes same ExitEvent
3. Uses same OMS layer
4. Reuses same audit trail

No changes to monitoring service needed.

---

## 2. POSITION STATE MACHINE

### 2.1 State Transitions (Complete Lifecycle)

```
INITIAL
  ↓
OPEN (position exists, qty != 0)
  │
  ├─→ [EXIT SIGNAL TRIGGERED]
  │
  ├─→ EXIT_PENDING
  │   └─ Exit decision made
  │   └─ Order about to be created
  │   └─ Waiting for OMS to accept
  │   └─ Timeout: 30 seconds
  │   └─ If timeout: Retry or alert
  │   └─ If order created: move to EXIT_SUBMITTED
  │   └─ If order rejected: revert to OPEN
  │
  ├─→ EXIT_SUBMITTED
  │   └─ Exit order created in OMS
  │   └─ Order state: CREATED/VALIDATED/ACCEPTED
  │   └─ Waiting for execution
  │   └─ Timeout: 300 seconds (5 minutes)
  │   └─ If timeout: Alert (order stuck)
  │   └─ If executed: move to CLOSED
  │   └─ If cancelled: revert to OPEN
  │
  ├─→ CLOSED (position qty = 0)
  │   └─ Exit order executed
  │   └─ Quantity = 0
  │   └─ Realized P&L finalized
  │   └─ End state
  │
  └─→ ERROR
      └─ Unexpected state
      └─ Requires manual intervention
```

### 2.2 Database Representation

```sql
ALTER TABLE portfolio_positions ADD (
  position_state VARCHAR(20) DEFAULT 'OPEN',
  -- Values: OPEN, EXIT_PENDING, EXIT_SUBMITTED, CLOSED, ERROR
  
  last_exit_attempt_at TIMESTAMP,
  last_exit_reason VARCHAR(50),
  exit_order_id UUID,
  
  position_state_updated_at TIMESTAMP,
  position_state_update_reason VARCHAR(500)
);

-- Position state must match quantity
-- OPEN: qty != 0
-- CLOSED: qty = 0
-- EXIT_PENDING/EXIT_SUBMITTED: qty != 0 (transitional)
```

### 2.3 State Transition Logic

```
OPEN → EXIT_PENDING:
  Trigger: Exit decision made (target/stop hit)
  Action:  Update position_state = 'EXIT_PENDING'
  Guard:   position_state == 'OPEN'
  
OPEN ← EXIT_PENDING:
  Trigger: Order rejected by OMS (risk check failed, etc.)
  Action:  Update position_state = 'OPEN'
  Guard:   Timeout > 30 seconds
  
EXIT_PENDING → EXIT_SUBMITTED:
  Trigger: Order successfully created in OMS
  Action:  Update position_state = 'EXIT_SUBMITTED'
           Update exit_order_id = order.id
  Guard:   position_state == 'EXIT_PENDING'
  
EXIT_SUBMITTED → CLOSED:
  Trigger: Execution recorded (qty = 0)
  Action:  Update position_state = 'CLOSED'
  Guard:   quantity == 0
  
EXIT_SUBMITTED ← OPEN:
  Trigger: Order cancelled or failed after submission
  Action:  Update position_state = 'OPEN'
  Guard:   Timeout > 300 seconds OR manual action
  
→ ERROR:
  Trigger: Any invalid transition
  Action:  Update position_state = 'ERROR'
           Alert operations team
  Guard:   Manual recovery required
```

### 2.4 Constraints and Validations

```
CONSTRAINT: position_state matches quantity
  IF position_state IN ('OPEN', 'EXIT_PENDING', 'EXIT_SUBMITTED')
     THEN quantity != 0
  IF position_state = 'CLOSED'
     THEN quantity = 0
```

---

## 3. SCHEDULER DESIGN

### 3.1 Timing & Frequency

**Phase 1: Conservative approach**
```
Interval:       30 seconds (NOT 10 seconds)
Rationale:      Sufficient for manual traders
                Reduces load on database
                Easier to debug
                Room to optimize later

Execution model: Sequential per user (parallel possible later)
Timeout:        25 seconds (safety margin before next cycle)
Max positions:  100+ per cycle with batch processing
```

### 3.2 Implementation Design

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionMonitoringScheduler {
    
    private final PositionMonitoringService monitoringService;
    
    @Value("${stokr.position.monitor-enabled:true}")
    private boolean monitoringEnabled;
    
    @Value("${stokr.position.monitor-interval-seconds:30}")
    private long monitorIntervalSeconds;
    
    // Scheduled method (Spring Boot scheduling)
    @Scheduled(fixedDelayString = "${stokr.position.monitor-interval-ms:30000}")
    public void monitorPositions() {
        if (!monitoringEnabled) {
            log.debug("Position monitoring disabled");
            return;
        }
        
        long cycleStartTime = System.currentTimeMillis();
        int totalExitsCreated = 0;
        int totalErrors = 0;
        
        try {
            // Step 1: Get all users with open positions
            List<UUID> userIds = positionService.getUsersWithOpenPositions();
            
            // Step 2: Process each user (could be parallelized)
            for (UUID userId : userIds) {
                try {
                    int exitsCreated = monitoringService.processUserPositions(userId);
                    totalExitsCreated += exitsCreated;
                } catch (Exception ex) {
                    totalErrors++;
                    log.error("Error processing user {}: {}", userId, ex.getMessage());
                }
            }
            
            long cycleDuration = System.currentTimeMillis() - cycleStartTime;
            
            // Verify cycle completes before next iteration
            if (cycleDuration > (monitorIntervalSeconds * 1000 * 0.8)) {
                log.warn("Cycle took {}ms, approaching next cycle interval ({}ms)",
                    cycleDuration, monitorIntervalSeconds * 1000);
            }
            
            log.info("Position monitoring cycle completed: duration={}ms, "
                + "users_processed={}, exits_created={}, errors={}",
                cycleDuration, userIds.size(), totalExitsCreated, totalErrors);
                
        } catch (Exception ex) {
            log.error("Critical error in position monitoring cycle", ex);
            publishAlert("PositionMonitoringScheduler critical error: " + ex.getMessage());
        }
    }
}
```

### 3.3 Threading Model

**Conservative Phase 1 design:**
```
Main scheduler thread
  ↓
Sequential user processing
  ├─ User 1: Load positions → Evaluate → Create orders (if needed)
  ├─ User 2: Load positions → Evaluate → Create orders (if needed)
  ├─ User 3: Load positions → Evaluate → Create orders (if needed)
  └─ ...

Why sequential in Phase 1?
- Easier to debug
- Easier to understand data flow
- Easier to identify issues
- Room to parallelize in Phase 2
```

**Future (Phase 2+) parallelization:**
```
Thread pool (10-20 threads)
  ├─ Thread 1: Process user A
  ├─ Thread 2: Process user B
  ├─ Thread 3: Process user C
  └─ ...

Parallelization only after Phase 1 proves stable
```

### 3.4 Batch Processing Strategy

```
Per user:
  Step 1: Load all OPEN positions (1 SQL query)
  Step 2: Get symbols list from positions
  Step 3: Batch load current prices (1 query, not N)
  Step 4: For each position:
          - Check target_price vs current_price
          - Check stop_loss_price vs current_price
          - Create order if conditions met

Why batching?
- 100 positions = 1 price query (not 100)
- Significantly faster
- Less database load
- Cleaner code
```

---

## 4. MARKET DATA VALIDATION

### 4.1 Stale Data Protection

```
REQUIREMENT:
  IF price age > 15 seconds
     SKIP evaluation for that symbol

RATIONALE:
  - 15 seconds is conservative for intraday trading
  - Prevents exits on stale prices
  - Prevents false triggers on outdated data
  - Still responsive enough (next cycle in 30 seconds)
```

### 4.2 Implementation

```java
private boolean isMarketDataFresh(String symbol, Instant priceTimestamp) {
    long ageSeconds = Duration.between(priceTimestamp, Instant.now()).getSeconds();
    
    if (ageSeconds > 15) {
        log.warn("Stale market data for {}: age={}s, skipping evaluation",
            symbol, ageSeconds);
        return false;
    }
    
    return true;
}

// Usage
for (PortfolioPosition position : openPositions) {
    BigDecimal currentPrice = priceData.get(position.getSymbol());
    Instant priceTimestamp = priceTimestamps.get(position.getSymbol());
    
    if (!isMarketDataFresh(position.getSymbol(), priceTimestamp)) {
        log.debug("Skipping evaluation for {} (stale price)", position.getSymbol());
        continue;  // Skip this position, retry next cycle
    }
    
    // Proceed with evaluation
    evaluatePosition(position, currentPrice);
}
```

### 4.3 Fallback Behavior

```
Scenario 1: Price data temporarily unavailable
  Action: Skip cycle for that symbol
  Next:   Retry in 30 seconds
  Alert:  Log warning

Scenario 2: Price data consistently unavailable (5+ cycles)
  Action: Skip cycle
  Next:   Retry in 30 seconds
  Alert:  Alert operations team
  
Scenario 3: Price older than 15 seconds
  Action: Skip cycle
  Next:   Retry in 30 seconds
  Alert:  Log debug message
```

### 4.4 Logging Strategy

```
DEBUG (development only):
  "Stale price for SBIN: age=22s, skipping"
  "Price data unavailable for TCS: using fallback"

WARN (monitor but not alert):
  "Market data fetch slow: took 4500ms"
  "Price data consistently stale for INFY"

ERROR (requires investigation):
  "Market data service unavailable"
  "Database query timeout on price data"

CRITICAL (requires immediate action):
  "Multiple cycles with stale data - monitoring paused"
```

---

## 5. SESSION CONTROLS

### 5.1 Market Hours

```
Trading hours (IST):
  Monday-Friday: 09:15 - 15:30
  Saturday-Sunday: CLOSED
  Holidays: See holiday calendar

Position monitoring:
  ACTIVE:     09:15 - 15:30 (normal trading hours)
  PAUSED:     15:30 - 09:15 (after hours)
  PAUSED:     Weekends
  PAUSED:     Holidays
  PAUSED:     Maintenance windows
```

### 5.2 Implementation

```java
@Component
@RequiredArgsConstructor
public class SessionValidator {
    
    private final HolidayService holidayService;
    
    public boolean isMarketOpen() {
        // Check time
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime currentTime = now.toLocalTime();
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        
        // Market hours: 09:15 - 15:30 IST
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);
        
        // Check day
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;  // Market closed
        }
        
        // Check if holiday
        if (holidayService.isHoliday(now.toLocalDate())) {
            return false;  // Market closed
        }
        
        // Check time
        return currentTime.isAfter(marketOpen) && currentTime.isBefore(marketClose);
    }
    
    public boolean isMaintenance() {
        // Check if in maintenance window
        // Maintenance windows: typically 15:30-16:00 IST (after market close)
        return false;  // Implement based on your maintenance schedule
    }
}

// Usage in scheduler
@Scheduled(fixedDelayString = "${stokr.position.monitor-interval-ms:30000}")
public void monitorPositions() {
    if (!sessionValidator.isMarketOpen()) {
        log.debug("Market not open, skipping position monitoring");
        return;
    }
    
    if (sessionValidator.isMaintenance()) {
        log.debug("Maintenance window, skipping position monitoring");
        return;
    }
    
    // Proceed with monitoring
    performMonitoring();
}
```

### 5.3 Holiday Configuration

```properties
# application.properties
stokr.trading.holidays=2026-06-10,2026-06-20,2026-07-17,2026-08-15

# Or external calendar service
stokr.holiday-service=NSEHolidayService
```

---

## 6. ENVIRONMENT ISOLATION

### 6.1 Requirement

```
Platform has 4 execution environments:
  LIVE       - Real trading with real money
  PAPER      - Paper trading with same data feed
  SIMULATION - Simulated with historical data
  REPLAY     - Playback of past trading sessions

Monitoring service MUST:
  - Process LIVE positions independently
  - Process PAPER positions independently
  - NOT mix environments
  - NOT exit LIVE based on PAPER triggers
  - Audit trail must show environment
```

### 6.2 Filtering Strategy

```java
@Service
@RequiredArgsConstructor
public class PositionMonitoringService {
    
    private final PortfolioPositionRepository positionRepository;
    
    // Tag positions by environment at load time
    private List<PortfolioPosition> getOpenPositions(UUID userId, Environment environment) {
        return positionRepository.findByUserIdAndEnvironmentAndStatus(
            userId,
            environment,
            "OPEN"
        );
    }
    
    // Load only user's LIVE positions
    private void processLiveEnvironment(UUID userId) {
        List<PortfolioPosition> livePositions = 
            getOpenPositions(userId, Environment.LIVE);
        processPositions(userId, livePositions, Environment.LIVE);
    }
    
    // Load only user's PAPER positions
    private void processPaperEnvironment(UUID userId) {
        List<PortfolioPosition> paperPositions = 
            getOpenPositions(userId, Environment.PAPER);
        processPositions(userId, paperPositions, Environment.PAPER);
    }
}
```

### 6.3 Database Schema

```sql
ALTER TABLE portfolio_positions ADD (
  environment VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  -- Values: LIVE, PAPER, SIMULATION, REPLAY
  
  CONSTRAINT chk_environment CHECK (
    environment IN ('LIVE', 'PAPER', 'SIMULATION', 'REPLAY')
  )
);

-- Ensure environment is used in all queries
CREATE INDEX idx_env_user_status 
ON portfolio_positions(environment, user_id, position_state);
```

### 6.4 Validation Checks

```java
private void validateEnvironmentConsistency(PortfolioPosition position, 
                                            Environment requestedEnv) {
    if (!position.getEnvironment().equals(requestedEnv)) {
        throw new EnvironmentMismatchException(
            String.format("Position environment (%s) does not match requested environment (%s)",
                position.getEnvironment(), requestedEnv));
    }
}

// Before creating exit order
ExitDecision decision = createExitDecision(position);

// Validate environment
validateEnvironmentConsistency(position, decision.getEnvironment());

// Create order (ORDER goes to correct broker credentials for environment)
if (decision.getEnvironment() == Environment.LIVE) {
    // Use LIVE broker credentials (real Zerodha account)
} else if (decision.getEnvironment() == Environment.PAPER) {
    // Use PAPER broker credentials (paper trading account)
} else {
    // SIMULATION/REPLAY: simulate order without real broker
}
```

---

## 7. EXIT REASON FRAMEWORK

### 7.1 Standard Exit Reasons

```
PHASE 1 ONLY:
  TARGET_HIT
    Definition: current_price >= target_price (for long)
                current_price <= target_price (for short)
    Use case: Take profit at planned target

  STOP_LOSS_HIT
    Definition: current_price <= stop_loss (for long)
                current_price >= stop_loss (for short)
    Use case: Cut losses at planned stop

FUTURE (Phase 2+):
  RSI_EXIT
    Definition: RSI-based exit (not Phase 1)
    
  AI_EXIT
    Definition: AI-driven exit (not Phase 1)
    
  FORCED_SQUAREOFF
    Definition: Risk limit breached, forced close
    
  MANUAL_EXIT
    Definition: User manually flattened position
    
  RISK_LIMIT
    Definition: Margin/exposure limit exceeded
    
  SYSTEM_EXIT
    Definition: System maintenance or emergency shutdown
```

### 7.2 Storage and Audit

```sql
ALTER TABLE exit_events ADD (
  exit_reason VARCHAR(50) NOT NULL,
  -- Values: TARGET_HIT, STOP_LOSS_HIT, RSI_EXIT, AI_EXIT, etc.
  
  reason_details JSON,
  -- For complex reasons: {"atr": 5.2, "volatility_factor": 1.3, etc.}
  
  CHECK (exit_reason IN (
    'TARGET_HIT', 'STOP_LOSS_HIT', 'RSI_EXIT', 'AI_EXIT',
    'FORCED_SQUAREOFF', 'MANUAL_EXIT', 'RISK_LIMIT', 'SYSTEM_EXIT'
  ))
);

-- Audit table schema
CREATE TABLE position_exit_audit (
  id SERIAL PRIMARY KEY,
  timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
  user_id UUID NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  position_id UUID NOT NULL,
  entry_price DECIMAL(15,4) NOT NULL,
  exit_price DECIMAL(15,4) NOT NULL,
  quantity DECIMAL(24,8) NOT NULL,
  exit_reason VARCHAR(50) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  exit_order_id UUID,
  strategy_name VARCHAR(100),
  realized_pnl DECIMAL(15,2),
  
  CONSTRAINT fk_exit_reason FOREIGN KEY (exit_reason)
    REFERENCES exit_reasons(code),
  
  INDEX idx_user_time (user_id, timestamp DESC),
  INDEX idx_symbol_time (symbol, timestamp DESC),
  INDEX idx_environment (environment),
  INDEX idx_exit_reason (exit_reason)
);
```

---

## 8. AUDIT ARCHITECTURE

### 8.1 Complete Audit Flow

```
USER ACTION
  ↓
[Market data check: price < 15s old]
  ↓
[Session check: market open]
  ↓
[Environment check: LIVE/PAPER isolated]
  ↓
[Position evaluation: target/stop hit?]
  ↓
[Exit decision made]
  ├─ Record ExitDecision (memory)
  ├─ Publish ExitEvent (domain event)
  │
  ├─→ AuditListener (async)
  │   ├─ INSERT position_exit_audit
  │   ├─ Record: timestamp, price, reason, environment
  │   └─ Log: "EXIT DECISION MADE: SBIN target=1008, current=1008.50"
  │
  ├─→ MetricsListener (async)
  │   ├─ Increment: exits_created counter
  │   ├─ Update: avg_decision_latency gauge
  │   └─ Track: exits_by_reason histogram
  │
  ├─→ OMS Integration (sync)
  │   ├─ Check: duplicate orders in last 30s?
  │   ├─ Create: OrderRequest
  │   ├─ Call: OrderPlacementService.place()
  │   ├─ Record: exit_order_id in position
  │   ├─ Update: position_state = EXIT_PENDING
  │   └─ Catch: OMS failures → log + alert
  │
  └─→ ComplianceListener (async)
      ├─ Verify: exit matches policy (environment correct, etc.)
      ├─ Alert: If policy violation detected
      └─ Report: To compliance team
```

### 8.2 Database Audit Tables

```sql
-- Core audit table
CREATE TABLE position_exit_audit (
  id SERIAL PRIMARY KEY,
  
  -- When
  timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
  
  -- Who
  user_id UUID NOT NULL,
  
  -- What
  position_id UUID NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  entry_price DECIMAL(15,4) NOT NULL,
  exit_price DECIMAL(15,4) NOT NULL,
  quantity DECIMAL(24,8) NOT NULL,
  realized_pnl DECIMAL(15,2),
  
  -- Why
  exit_reason VARCHAR(50) NOT NULL,
  
  -- Where
  environment VARCHAR(20) NOT NULL,
  
  -- How
  strategy_name VARCHAR(100),
  exit_order_id UUID,
  
  -- Audit trail
  created_by_service VARCHAR(100) DEFAULT 'PositionMonitoringService',
  
  -- Indexes for fast queries
  INDEX idx_user_time (user_id, timestamp DESC),
  INDEX idx_symbol_time (symbol, timestamp DESC),
  INDEX idx_reason (exit_reason),
  INDEX idx_environment (environment)
);

-- Event log (immutable)
CREATE TABLE position_exit_events (
  id SERIAL PRIMARY KEY,
  event_type VARCHAR(50),  -- DECISION_MADE, ORDER_CREATED, EXECUTION_RECORDED
  event_data JSON,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  
  INDEX idx_type_time (event_type, created_at DESC)
);
```

### 8.3 Event Publishing

```java
// When exit decision made
public void recordExitAudit(ExitEvent exitEvent) {
    // INSERT into position_exit_audit
    positionExitAuditRepository.save(PositionExitAudit.from(exitEvent));
    
    // Publish domain event (async listeners)
    applicationEventPublisher.publishEvent(exitEvent);
    
    log.info("Exit audit recorded: user={}, symbol={}, reason={}, price={}, "
        + "environment={}, timestamp={}",
        exitEvent.getUserId(),
        exitEvent.getSymbol(),
        exitEvent.getExitReason(),
        exitEvent.getExitPrice(),
        exitEvent.getEnvironment(),
        exitEvent.getTimestamp());
}
```

---

## 9. FAILURE RECOVERY ARCHITECTURE

### 9.1 Failure Scenarios

```
FAILURE SCENARIO 1: OMS Unavailable
─────────────────────────────────────
When:     OrderPlacementService throws exception
Status:   Position remains OPEN (no state change)
Recovery:
  - Log error
  - Alert operations team
  - Retry automatically in next cycle (30 seconds)
  - Position re-evaluated at next cycle
  - If fails 5 cycles: escalate to WARNING alert
Code:
  try {
    orderService.place(request);
    // Update to EXIT_PENDING
  } catch (OmsUnavailableException ex) {
    log.error("OMS unavailable, retry next cycle", ex);
    // Position stays OPEN - will retry automatically
    throw ex;  // Fail this position, continue with others
  }

FAILURE SCENARIO 2: Broker Unavailable
───────────────────────────────────────
When:     Order reaches broker, broker rejects
Status:   Position state: EXIT_PENDING → OPEN
Recovery:
  - Order rejected by broker (OrderPlacementService handles)
  - Position reverts to OPEN
  - Automatically re-evaluated in next cycle
  - Monitor for pattern (if always rejects = alert)
Code:
  Position state management:
    EXIT_PENDING → OPEN (on broker failure)
    Auto-retry next cycle

FAILURE SCENARIO 3: Database Unavailable
──────────────────────────────────────────
When:     Database connection fails
Status:   Cycle aborts gracefully
Recovery:
  - Log critical error
  - Skip this cycle completely
  - No state changes
  - Retry in next cycle (30 seconds)
  - Operations alerted after 3 failures
Code:
  if (databaseUnavailable) {
    log.error("Database unavailable, skipping cycle");
    publishAlert("PositionMonitoring: database unavailable");
    return;  // Exit, retry next cycle
  }

FAILURE SCENARIO 4: Scheduler Crash
────────────────────────────────────
When:     Application crashes or restarts
Recovery:
  - Spring's @Scheduled restarted by container
  - Picks up from NEXT scheduled interval
  - Re-scans all positions from OPEN state
  - Orders in flight (EXIT_PENDING/EXIT_SUBMITTED) resume naturally
  - No duplicate exits (state machine prevents)
Code:
  Position states at restart:
    OPEN:            Re-evaluated in next cycle
    EXIT_PENDING:    Retried (duplicate check prevents re-order)
    EXIT_SUBMITTED:  Monitored via OrderService
    CLOSED:          Ignored (qty=0)

FAILURE SCENARIO 5: Duplicate Order Attempts
──────────────────────────────────────────────
When:     Scheduler cycle overlap OR network retry
Recovery:
  - Application check: "order exists for this symbol in last 30s?"
  - Database unique constraint: prevents duplicate insert
  - OrderPlacementService idempotency: same order ID returned
Code:
  // Triple-check before creating
  if (hasRecentExitOrder(position)) {
    log.debug("Exit order already exists, skipping");
    return;  // Skip, will update state when order executes
  }
  
  // Create with idempotency key
  order = orderService.place(request, idempotencyKey);

FAILURE SCENARIO 6: Application Restart with EXIT_PENDING Positions
─────────────────────────────────────────────────────────────────
When:     App restarts while position is EXIT_PENDING
Recovery:
  At restart:
    - Position still in EXIT_PENDING state
    - exit_order_id already recorded
    - Next cycle loads this position
    - Duplicate check: "order already exists?" → YES
    - Skip re-creating
    - Monitor order progress via OMS
Code:
  Restart recovery:
    SELECT * FROM positions WHERE position_state = 'EXIT_PENDING'
    For each position:
      - Check if order_id exists in OMS
      - If exists: Monitor status
      - If missing: Check last_exit_attempt_at timestamp
      - If > 5 min ago: Mark as ERROR, alert

FAILURE SCENARIO 7: Price Data Stale (> 15 seconds)
────────────────────────────────────────────────────
When:     Market data feed slow or unavailable
Recovery:
  - Detect: price age > 15 seconds
  - Action: Skip evaluation for that symbol
  - Next:   Retry in next cycle (30 seconds)
  - Log:    debug message (not an error)
Code:
  if (priceAgeSeconds > 15) {
    log.debug("Stale price for {}, skipping", symbol);
    continue;  // Skip this position
  }
```

### 9.2 Retry Strategy

```
Position exits with stale/missing price data:
  Cycle 1: Price unavailable → Skip (log debug)
  Cycle 2 (30s later): Retry
  Cycle 3 (60s later): Retry
  Cycle 4 (90s later): Retry
  Cycle 5 (120s later): Retry
  Cycle 6 (150s later): Price still unavailable → Alert
  
OMS failures:
  Cycle 1: OrderPlacementService.place() fails → Log error
  Cycle 2 (30s later): Retry (position still OPEN)
  Cycle 3 (60s later): Retry
  Cycle 4 (90s later): Retry
  Cycle 5 (120s later): OMS still down → Alert operations

Broker rejections:
  Order rejected → Revert position to OPEN (not ERROR)
  Next cycle: Re-evaluate from scratch
  If pattern: Alert (broker consistently rejects)
```

### 9.3 Alert Strategy

```
DEBUG (development):
  "Stale price for SBIN, skipping"
  "Order already exists for INFY in last 30s"

INFO (normal operation):
  "Exit order created: SBIN target hit"
  "Position state: OPEN → EXIT_PENDING"

WARN (needs attention):
  "OMS unavailable, retry next cycle"
  "Price data unavailable for 3+ cycles"

ERROR (requires investigation):
  "Database connection failed"
  "Position state became OPEN from EXIT_PENDING (broker rejection)"

CRITICAL (requires immediate action):
  "Multiple cycles failing - monitoring paused"
  "Database unavailable for 5+ minutes"
  "Duplicate exit orders detected"
```

---

## 10. ROLLOUT PLAN

### 10.1 Phased Deployment

```
PHASE 1: STAGING (Internal Testing)
───────────────────────────────────
Day 1-2: Deploy to staging environment
  - Enable monitoring service
  - Configure 30-second intervals
  - Load synthetic test positions
  - Verify state machine transitions
  - Verify audit trail
  
Day 3: Load testing
  - Simulate 50 concurrent users
  - Simulate 100+ positions
  - Verify database performance
  - Verify scheduler doesn't overrun
  
Approval gate: All tests pass, no errors in logs

PHASE 2: PRODUCTION - ROLLOUT
──────────────────────────────
Step 1 (8:00 AM): Deploy to production (blue environment)
  - New deployment running alongside old
  - Monitoring disabled by default: stokr.position.monitor-enabled=false
  - Canary feature flag: stokr.position.monitor-users=[]
  - Zero users affected yet

Step 2 (8:30 AM): Enable for 1 test account
  - Select 1 internal test account (< 5 positions)
  - Set: stokr.position.monitor-users=[test-user-123]
  - Monitor for 30 minutes
  - Verify: exits trigger correctly
  - Verify: audit trail complete
  - Watch: database performance
  
If issues found:
  - Disable immediately (rollback step)
  - Debug
  - Fix
  - Redeploy
  - Start over at Step 2

Step 3 (9:15 AM): Enable for 5 small accounts
  - Select 5 accounts with 1-5 positions each
  - Set: stokr.position.monitor-users=[test-user-123, user-456, user-789, ...]
  - Monitor for 30 minutes
  - Expected: 0-5 exits
  - Verify: no duplicate exits
  - Verify: no environment mixing

Step 4 (9:45 AM): Enable for 10% of user base
  - Set: stokr.position.monitor-users=[list of 10% users]
  - Monitor for 60 minutes
  - Expected: 10-50 exits
  - Alert on: any anomalies
  - Database performance: should show <5% additional load

Step 5 (11:00 AM): Enable for 50% of user base
  - Monitor for 120 minutes
  - Expected: 50-250 exits
  - Alert on: duplicate exits, environment mixing
  - Verify: no scheduler overruns

Step 6 (1:00 PM): Enable for 100% of user base
  - No more canary restrictions
  - Full production deployment
  - Monitor for rest of day
  - Report: summary of exits, performance

PHASE 3: STABILIZATION
─────────────────────
Days 2-7: Monitor continuously
  - Daily reports: exits, performance, errors
  - Watch for edge cases
  - Respond to any issues immediately
  - Collect metrics for Phase 2+ improvements

PHASE 4: OPTIMIZATION
─────────────────────
Week 2+: Once stable
  - Analyze exit distribution
  - Measure accuracy of AI-driven exits
  - Consider reducing interval to 20 seconds (if needed)
  - Consider parallelizing user processing (if needed)
```

### 10.2 Feature Flag Configuration

```properties
# application.properties

# Master enable/disable
stokr.position.monitor-enabled=true

# Target users (empty = disabled for all)
# Populated during rollout phases
stokr.position.monitor-users=

# Configuration
stokr.position.monitor-interval-ms=30000
stokr.position.monitor-batch-size=100
stokr.position.monitor-max-price-age-seconds=15

# Alert thresholds
stokr.position.monitor-alert-consecutive-errors=3
stokr.position.monitor-alert-cycle-latency-ms=8000

# Logging
stokr.position.monitor-log-level=INFO
```

### 10.3 Rollback Procedure (30-Second Guarantee)

```
IF CRITICAL ISSUE DETECTED:

Step 1 (5 seconds): Disable feature flag
  Update application.properties:
    stokr.position.monitor-enabled=false
  
  Command:
    kubectl patch configmap app-config \
      -p '{"data":{"stokr.position.monitor-enabled":"false"}}'

Step 2 (10 seconds): Force config reload
  POST /api/admin/config/reload
  
  All instances reload within 5 seconds

Step 3 (15 seconds): Verify disabled
  curl http://localhost:8080/api/health/monitor
  
  Response should show: "monitoring_enabled": false

Step 4 (20 seconds): Verify no new exit orders
  SELECT COUNT(*) FROM orders 
  WHERE created_at > NOW() - INTERVAL 1 MINUTE
  AND order_reason = 'POSITION_MONITORING_SERVICE'
  
  Should be 0 (or same as before)

Step 5 (25 seconds): Assess damage
  SELECT COUNT(*) FROM position_exit_events
  WHERE created_at > [start of issue]
  
  Report: X exits created before rollback
  Investigate: Are they valid exits?

Step 6 (30 seconds+): Decide
  - If exits were valid: Keep rollback, investigate logging issue
  - If exits were invalid: Revert positions (manual process)
  - Either way: Don't push back without fix

Total time: < 30 seconds

Preventive monitoring:
  - Dashboard shows: exits created per minute
  - Alert if: > 10 exits in 1 minute (possible runaway)
  - Alert if: same symbol exits twice in 5 minutes
  - Alert if: environment mixing detected
```

### 10.4 Monitoring Dashboard

```
REAL-TIME METRICS (Update every 10 seconds):

Position Monitoring Status
  Monitoring Active:        YES / NO
  Scheduled Intervals:      30 seconds
  Last Cycle Completed:     30 seconds ago
  Cycle Duration:           2.3 seconds (avg)
  
Exit Activity (Last 60 minutes)
  Exits Created:            47
  By Reason:
    - TARGET_HIT:          32 (68%)
    - STOP_LOSS_HIT:       15 (32%)
  By Environment:
    - LIVE:                 35 (74%)
    - PAPER:               12 (26%)
    - SIMULATION:           0
    - REPLAY:               0
  
Performance
  DB Query Latency:         145ms (avg)
  Market Data Latency:      203ms (avg)
  Total Cycle Latency:      2.3s (avg)
  Scheduler Health:         ✓ Normal
  
Errors (Last 60 minutes)
  Stale Price Data:         2 symbols
  OMS Failures:            0
  Broker Rejections:        0
  Database Errors:         0
  Duplicate Orders:         0
  
Alerts
  🟢 All systems normal
  
Rollback Button
  Status:  READY (< 30 seconds)
  Last used: Never
```

---

## 11. IMPLEMENTATION EFFORT ESTIMATE

### 11.1 Development Phases

```
PHASE 1: Core Exit Monitoring (Week 1)
──────────────────────────────────────
Task 1: Scheduler Setup
  - PositionMonitoringScheduler class
  - Spring @Scheduled configuration
  - Error handling & logging
  Effort: 4 hours
  
Task 2: Exit Decision Layer
  - ExitDecision class (immutable)
  - ExitEvent domain event
  - Exit reason enum
  Effort: 2 hours
  
Task 3: Position Evaluation
  - Market data fetching (batch)
  - Price freshness check (> 15 seconds)
  - Target/stop comparison
  Effort: 6 hours
  
Task 4: State Machine
  - OPEN → EXIT_PENDING → EXIT_SUBMITTED → CLOSED
  - State transitions & guards
  - Timeout handling
  Effort: 6 hours
  
Task 5: OMS Integration
  - Create OrderRequest from ExitDecision
  - Call OrderPlacementService.place()
  - Handle failures
  Effort: 4 hours
  
Task 6: Audit Trail
  - position_exit_audit table
  - Event logging
  - Compliance tracking
  Effort: 4 hours
  
Task 7: Environment Isolation
  - Filter LIVE/PAPER/SIMULATION separately
  - Validation checks
  - Route to correct broker credentials
  Effort: 4 hours
  
Task 8: Session Controls
  - Market hours validation
  - Holiday calendar integration
  - Maintenance window handling
  Effort: 3 hours
  
Task 9: Testing
  - Unit tests (state machine, decision logic)
  - Integration tests (OMS integration)
  - Concurrency tests
  Effort: 8 hours
  
TOTAL PHASE 1: 41 hours (5-6 days)

PHASE 2: Deployment & Validation (1 week)
──────────────────────────────────────────
Task 1: Staging deployment
  - Build Docker image
  - Deploy to staging
  - Integration testing
  Effort: 4 hours
  
Task 2: Blue-green deployment setup
  - Configure feature flags
  - Setup canary rollout
  - Monitoring dashboard
  Effort: 4 hours
  
Task 3: Production rollout
  - Execute phased deployment
  - Monitor each phase
  - Document results
  Effort: 8 hours (during trading hours)
  
Task 4: Stabilization
  - Monitor for issues
  - Fix edge cases
  - Collect metrics
  Effort: 8 hours (distributed)
  
TOTAL PHASE 2: 24 hours (distributed over 1 week)

PHASE 3: Optimization (Optional, Week 3+)
──────────────────────────────────────────
- Reduce monitoring interval (30s → 20s → 10s)
- Parallelize user processing
- Add ML models for exit optimization
Effort: TBD based on performance data
```

### 11.2 Code Size Estimate

```
Core Components:
  PositionMonitoringScheduler:    150 lines
  ExitDecisionService:            200 lines
  PositionEvaluator:              250 lines
  StateTransitionManager:         200 lines
  AuditService:                   150 lines
  EnvironmentValidator:           100 lines
  SessionValidator:               100 lines
  
Supporting:
  Domain objects (ExitDecision, ExitEvent):  100 lines
  Configuration classes:                      80 lines
  Exception handling:                         50 lines
  
Total: ~1,380 lines (4-5 Java files, ~300-400 lines each)
```

### 11.3 Database Changes

```
CREATE: 2 new tables
  - position_exit_audit (audit trail)
  - position_exit_events (event log)

ALTER: portfolio_positions table
  - Add: position_state, exit_order_id, last_exit_attempt_at, etc.
  - Add: 2-3 indexes for fast queries

ALTER: orders table
  - Add: exit_trigger_reason, exit_triggered_at (optional)

Total: ~200 lines SQL
```

---

## 12. CLASS DIAGRAM

```
┌────────────────────────────────────────────────────────────┐
│ PositionMonitoringScheduler (@Scheduled every 30s)         │
│                                                             │
│ - monitorPositions()                                       │
│ - processUserPositions(userId)                             │
└────────────┬────────────────────────────────────────────────┘
             │ uses
             ↓
┌────────────────────────────────────────────────────────────┐
│ SessionValidator                                            │
│                                                             │
│ - isMarketOpen(): boolean                                  │
│ - isMaintenance(): boolean                                 │
│ - isHoliday(date): boolean                                 │
└────────────┬────────────────────────────────────────────────┘
             │ uses
             ↓
┌────────────────────────────────────────────────────────────┐
│ MarketDataService (batch load)                             │
│                                                             │
│ - getCurrentPrices(symbols): Map<String, BigDecimal>      │
│ - getPriceTimestamps(symbols): Map<String, Instant>       │
└────────────┬────────────────────────────────────────────────┘
             │ uses
             ↓
┌────────────────────────────────────────────────────────────┐
│ PositionEvaluator                                           │
│                                                             │
│ - evaluatePosition(position, price)                        │
│ - shouldExit(position, price): boolean                     │
│ - getExitReason(position, price): ExitReason              │
└────────────┬────────────────────────────────────────────────┘
             │ creates
             ↓
┌────────────────────────────────────────────────────────────┐
│ ExitDecision (immutable)                                   │
│                                                             │
│ - position_id: UUID                                        │
│ - symbol: String                                           │
│ - exit_reason: ExitReason (TARGET_HIT / STOP_LOSS_HIT)    │
│ - exit_price: BigDecimal                                   │
│ - decision_timestamp: Instant                              │
│ - environment: Environment (LIVE/PAPER/etc.)              │
└────────────┬────────────────────────────────────────────────┘
             │ published as
             ↓
┌────────────────────────────────────────────────────────────┐
│ ExitEvent (domain event)                                   │
│                                                             │
│ - timestamp: Instant                                       │
│ - position_id: UUID                                        │
│ - symbol: String                                           │
│ - entry_price: BigDecimal                                  │
│ - exit_price: BigDecimal                                   │
│ - exit_reason: ExitReason                                  │
│ - environment: Environment                                 │
│ - user_id: UUID                                            │
│ - strategy_name: String                                    │
│                                                             │
│ Event Listeners (async):                                   │
│ - AuditEventListener (INSERT into position_exit_audit)     │
│ - MetricsEventListener (update gauges, counters)          │
│ - ComplianceEventListener (verify policy)                 │
└────────────┬────────────────────────────────────────────────┘
             │ processed by
             ↓
┌────────────────────────────────────────────────────────────┐
│ OMS Integration Layer                                       │
│                                                             │
│ - DuplicateExitChecker.hasRecentOrder()                    │
│ - StateTransitionManager                                    │
│ - OrderPlacementService.place() (external)                │
│                                                             │
│ State transitions:                                         │
│   OPEN → EXIT_PENDING → EXIT_SUBMITTED → CLOSED          │
└────────────────────────────────────────────────────────────┘
```

---

## 13. CONCLUSION

This design provides:

✅ **Reliability**
  - State machine prevents duplicates
  - Failure recovery (automatic retries)
  - Rollback in < 30 seconds

✅ **Safety**
  - Market data validation (> 15 seconds = skip)
  - Session controls (market hours only)
  - Environment isolation (LIVE/PAPER separate)
  - Audit trail (every decision logged)

✅ **Scalability**
  - Batch processing (100+ positions per cycle)
  - Sequential processing (can parallelize in Phase 2)
  - Conservative 30-second interval (can optimize)

✅ **Extensibility**
  - ExitDecision layer is reusable
  - Future exit mechanisms (RSI, AI) use same architecture
  - No changes to OMS needed
  - Clean separation of concerns

✅ **Operability**
  - Feature flags for controlled rollout
  - Monitoring dashboard
  - Clear alert levels
  - Documented rollback

**Ready to implement Phase 1?**

