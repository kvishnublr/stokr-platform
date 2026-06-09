# PHASE 1 IMPLEMENTATION CHECKLIST
## Automatic Position Monitoring & Exit Execution

**Version:** 1.0  
**Date:** June 9, 2026  
**Scope:** Build plan for PositionMonitoringService based on actual Stokr codebase  

---

## PART 1: ARCHITECTURE VALIDATION REPORT

### Question 1: Where is target_price stored?

**Answer:**
- **Table:** `oms_orders` (PostgreSQL)
- **Column:** `target_price` (DECIMAL 24,8)
- **Java Class:** `OmsOrder`
- **Field:** `BigDecimal targetPrice`
- **Location:** `stokr-oms/src/main/java/com/stokr/oms/domain/OmsOrder.java:62`

**Current Usage:**
```java
@Column(name = "target_price", precision = 24, scale = 8)
private BigDecimal targetPrice;
```

**Set by:** OrderIntentProcessor.java:205 (copies from strategy signal)
**Accessed by:** None currently (stored but never evaluated for exits)

---

### Question 2: Where is stop_loss stored?

**Answer:**
- **Table:** `oms_orders` (PostgreSQL)
- **Column:** `stop_price` (DECIMAL 24,8)
- **Java Class:** `OmsOrder`
- **Field:** `BigDecimal stopPrice`
- **Location:** `stokr-oms/src/main/java/com/stokr/oms/domain/OmsOrder.java:59`

**Current Usage:**
```java
@Column(name = "stop_price", precision = 24, scale = 8)
private BigDecimal stopPrice;
```

**Set by:** OrderIntentProcessor.java:204 (copies from strategy signal)
**Accessed by:** None currently (stored but never evaluated for exits)

**Note:** `stop_loss` field does NOT exist on PortfolioPosition. Only on OmsOrder (entry orders).
**Design implication:** Exit monitoring must load the ENTRY order to get stop_loss, not the position.

---

### Question 3: Which table stores open positions?

**Answer:**
- **Table:** `portfolio_positions` (PostgreSQL)
- **Java Class:** `PortfolioPosition`
- **Location:** `stokr-oms/src/main/java/com/stokr/oms/domain/PortfolioPosition.java`

**Structure:**
```java
@Entity
@Table(name = "portfolio_positions")
public class PortfolioPosition extends BaseEntity {
    @Column(name = "user_id")
    private UUID userId;
    
    @Column(name = "symbol")
    private String symbol;
    
    @Column(name = "quantity")
    private BigDecimal quantity;
    
    @Column(name = "avg_price")
    private BigDecimal avgPrice;
    
    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;
    
    @Column(name = "unrealized_pnl")
    private BigDecimal unrealizedPnl;
    
    @Column(name = "mtm_price")
    private BigDecimal mtmPrice;
}
```

**Open position criteria:** `quantity != 0` and `deleted = false`

---

### Question 4: What uniquely identifies a position?

**Answer:**
- **Primary key:** `id` (UUID, from BaseEntity)
- **Composite key:** `(user_id, symbol)`
- **Status:** `quantity != 0` means OPEN

**Repository method:**
```java
PortfolioPosition findByUserIdAndSymbolAndDeletedFalse(UUID userId, String symbol)
```
**Location:** stokr-oms/repository/PortfolioPositionRepository.java

**Design implication:** 
- Cannot have duplicate (user_id, symbol) with qty != 0
- One position per user per symbol
- Position "closes" when qty becomes 0

---

### Question 5: How does OMS currently prevent duplicate orders?

**Answer:**
- **Mechanism:** Idempotency key + database lookup
- **Service:** `OrderLifecycleService.createOrGetIdempotent()`
- **Location:** `stokr-oms/src/main/java/com/stokr/oms/service/OrderLifecycleService.java:30-39`

**Code:**
```java
@Transactional
public OmsOrder createOrGetIdempotent(UUID userId, String idempotencyKey, OmsOrder draft) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
        Optional<OmsOrder> existing =
                orderRepository.findByUserIdAndIdempotencyKeyAndDeletedFalse(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();  // Return existing order, don't create new
        }
    }
    return persistNew(userId, idempotencyKey, draft);
}
```

**Database lookup:**
```java
Optional<OmsOrder> findByUserIdAndIdempotencyKeyAndDeletedFalse(UUID userId, String idempotencyKey)
```

**Design implication:** 
- Must generate unique idempotency key per position per exit
- Same key within 30 seconds returns same order (idempotent)
- Use timestamp or cycle number in key: `position-monitor:{position_id}:{timestamp}`

---

### Question 6: How is market price retrieved today?

**Answer:**
- **Service:** `MarketDataQueryService`
- **Location:** `stokr-marketdata/src/main/java/com/stokr/marketdata/service/MarketDataQueryService.java`
- **Method:** `lastBarsAsc()` (returns candles, not just price)
- **Repository:** `MarketdataCandleRepository`

**Current retrieval pattern:**
```java
public List<MarketdataCandle> lastBarsAsc(String symbol, String timeframe, int maxBars) {
    List<MarketdataCandle> desc = candleRepository
        .findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(symbol, timeframe);
    // ... sort and return
}
```

**Data structure:** `MarketdataCandle`
- Fields: symbol, timeframe, openTime, closePrice, highPrice, lowPrice, openPrice
- Used by: Strategy evaluation, backtesting, historical analysis

**Design implication:**
- Use `closePrice` as current price
- Load by symbol and timeframe (e.g., "1m", "5m")
- Most recent candle = current market price
- Timestamp on candle = `openTime` (when candle opened)

---

### Question 7: How is stale market data detected today?

**Answer:**
- **Current status:** NOT IMPLEMENTED
- **Detection needed:** Compare `candle.openTime` vs `Instant.now()`
- **Staleness threshold:** Must be defined (use 15 seconds per design)

**Where to implement:**
- New validation layer in PositionMonitoringService
- Check: `Duration.between(candle.openTime, Instant.now()).getSeconds() > 15`
- Action on stale: Skip evaluation, log warning, retry next cycle

**Code location for similar logic:**
```java
// Similar pattern in CandleFinalizationScheduler
// Location: stokr-marketdata/runtime/CandleFinalizationScheduler.java:20-22
@Scheduled(fixedDelayString = "${stokr.marketdata.partial-evict-ms:300000}")
public void evictStalePartials() {
    Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
    candleFinalizationService.evictPartialStateOlderThan(cutoff);
}
```

---

### Question 8: How does flattenOpenPositions() currently create exit orders?

**Answer:**
- **Service:** `TraderTerminalControlService`
- **Method:** `flattenOpenPositions()`
- **Location:** `stokr-bootstrap/src/main/java/com/stokr/bootstrap/trader/TraderTerminalControlService.java:193-235`

**Current implementation:**
```java
private int flattenOpenPositions(UUID userId, List<String> notes, 
                                 List<Map<String, Object>> flattenResults) {
    int created = 0;
    ExecutionMode mode = resolveExecutionMode(userId);
    List<PortfolioPosition> positions = 
        portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);
    
    for (PortfolioPosition p : positions) {
        if (p.getQuantity() == null || p.getQuantity().signum() == 0) {
            continue;
        }
        
        String side = p.getQuantity().signum() > 0 ? "SELL" : "BUY";
        
        OmsOrder o = orderPlacementService.place(userId, new CreateOrderRequest(
                p.getSymbol(),
                side,
                "MARKET",
                p.getQuantity().abs(),
                null,
                mode,
                mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM",
                "TERMINAL_FLATTEN",
                "terminal:flatten:" + userId + ":" + p.getSymbol() + ":" + Instant.now().toEpochMilli()
        ));
        
        created++;
    }
    return created;
}
```

**Key observations:**
1. Loads all open positions for user
2. Determines side: SELL if long (qty > 0), BUY if short (qty < 0)
3. Creates MARKET order with full quantity
4. Uses idempotency key: `terminal:flatten:{userId}:{symbol}:{timestamp}`
5. Calls `OrderPlacementService.place()` (same service we'll use)

**Design implication:**
- PositionMonitoringService will follow same pattern
- Different idempotency key: `position-monitor:{position_id}:{cycle_number}`
- Different reason: "POSITION_MONITORING_SERVICE" instead of "TERMINAL_FLATTEN"

---

### Question 9: Which service updates position status?

**Answer:**
- **Service:** `PortfolioAccountingService`
- **Method:** `applyFill(UUID userId, String symbol)`
- **Location:** `stokr-oms/src/main/java/com/stokr/oms/portfolio/PortfolioAccountingService.java:40-44`

**Trigger:** Called after OmsExecution is recorded (trade execution)

**Implementation:**
```java
@Transactional
public void applyFill(UUID userId, String symbol) {
    rebuildSymbol(userId, symbol);
    recordSnapshot(userId, ZoneId.of("Asia/Kolkata"));
}

@Transactional
public void rebuildSymbol(UUID userId, String symbol) {
    List<OmsExecution> executions = 
        executionRepository.findAllForUserAndSymbolOrdered(userId, symbol);
    
    Ledger ledger = new Ledger();
    for (OmsExecution e : executions) {
        String s = e.getOrder().getSide();
        ledger.apply(s, e.getFilledQty(), e.getAvgPrice());
    }
    
    PortfolioPosition pos = positionRepository
        .findByUserIdAndSymbolAndDeletedFalse(userId, symbol)
        .orElseGet(() -> {
            PortfolioPosition p = new PortfolioPosition();
            p.setUserId(userId);
            p.setSymbol(symbol);
            return p;
        });
    
    pos.setQuantity(ledger.netQty());  // Updates qty from all executions
    pos.setAvgPrice(ledger.netAvgForOpenPosition());
    pos.setRealizedPnl(ledger.realized());
    positionRepository.save(pos);
}
```

**Status update mechanism:**
- Recalculates quantity from all executions
- If exit order executes: quantity becomes 0 (position closed)
- If partial exit: quantity reduces
- If no execution: quantity unchanged

**Design implication:**
- Don't manually update position state
- Position "state" is implicit: qty = 0 means CLOSED
- PortfolioAccountingService.applyFill() handles state transition automatically
- Our monitoring service only needs to track ORDER state, not position state

---

### Question 10: Which service records execution fills?

**Answer:**
- **Service:** `ExecutionService` (records into OMS)
- **Repository:** `OmsExecutionRepository`
- **Table:** `oms_executions`
- **Trigger:** Broker execution confirmation

**Current flow:**
1. Order sent to broker (Zerodha)
2. Broker executes and returns execution confirmation
3. ExecutionService records in `oms_executions` table
4. Event published → PortfolioAccountingService.applyFill() called
5. Position updated

**Database table structure:**
```sql
CREATE TABLE oms_executions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    order_id UUID NOT NULL,
    filled_qty DECIMAL(24,8),
    avg_price DECIMAL(15,4),
    execution_timestamp TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES oms_orders(id)
)
```

**Design implication:**
- Execution recording is ALREADY HANDLED by existing system
- Our monitoring service doesn't need to update executions
- Just create the exit ORDER, rest happens automatically
- Order → Broker → Execution → PortfolioAccountingService.applyFill() → Position update

---

## PART 2: IMPLEMENTATION CHECKLIST

### Phase 1.1: Configuration Layer

**Objective:** Add all configuration needed for PositionMonitoringService

**Deliverables:**

```
[ ] Create: PositionMonitoringConfig.java
    Location: stokr-oms/src/main/java/com/stokr/oms/config/
    Purpose: Spring configuration for scheduler and beans
    Includes:
      - Feature flag: stokr.position.monitor-enabled
      - Interval: stokr.position.monitor-interval-ms (default: 30000)
      - Batch size: stokr.position.monitor-batch-size (default: 100)
      - Market data freshness: stokr.position.monitor-max-price-age-seconds (default: 15)
      - Max price age seconds: stokr.position.monitor-max-price-age
      - Duplicate window: stokr.position.monitor-duplicate-window (default: 300)
      - Session validator bean
      - Market data validator bean

[ ] Create: application.properties entries
    File: stokr-oms/src/main/resources/application.properties
    Content:
      stokr.position.monitor-enabled=true
      stokr.position.monitor-interval-ms=30000
      stokr.position.monitor-batch-size=100
      stokr.position.monitor-max-price-age-seconds=15
      stokr.position.monitor-duplicate-window=300
      stokr.position.monitor-log-level=INFO

[ ] Create: application-profile.properties for each environment
    Files:
      - application-dev.properties
      - application-staging.properties
      - application-prod.properties
    Purpose: Environment-specific configuration overrides

[ ] Document: Configuration Guide
    Location: docs/POSITION_MONITORING_CONFIG.md
    Content: Tuning guide, thresholds, feature flag management
```

**Estimated effort:** 3 hours
**Dependencies:** None (pure configuration)

---

### Phase 1.2: Domain Layer

**Objective:** Create reusable domain objects for exit decisions

**Deliverables:**

```
[ ] Create: ExitReason.java enum
    Location: stokr-oms/src/main/java/com/stokr/oms/domain/
    Values:
      - TARGET_HIT
      - STOP_LOSS_HIT
      (Future: RSI_EXIT, AI_EXIT, RISK_LIMIT, etc.)

[ ] Create: ExitState.java enum
    Location: stokr-oms/src/main/java/com/stokr/oms/domain/
    Values:
      - OPEN
      - EXIT_PENDING
      - EXIT_SUBMITTED
      - CLOSED
      - ERROR

[ ] Create: ExitDecision.java (immutable model)
    Location: stokr-oms/src/main/java/com/stokr/oms/domain/
    Fields:
      - positionId: UUID
      - userId: UUID
      - symbol: String
      - entryPrice: BigDecimal
      - currentPrice: BigDecimal
      - exitReason: ExitReason
      - decisionTimestamp: Instant
      - marketDataTimestamp: Instant
      - environment: ExecutionEnvironment
      - strategyName: String
    Immutable (use Lombok @Value or record)

[ ] Create: ExitEvent.java (domain event)
    Location: stokr-oms/src/main/java/com/stokr/common/events/
    Extends: ApplicationEvent
    Fields:
      - timestamp: Instant
      - positionId: UUID
      - userId: UUID
      - symbol: String
      - entryPrice: BigDecimal
      - exitPrice: BigDecimal
      - exitReason: ExitReason
      - environment: ExecutionEnvironment
      - strategyName: String
      - orderId: UUID (null until order created)
      - quantity: BigDecimal
    Purpose: Published for listeners (audit, metrics, compliance)

[ ] Create: ExecutionEnvironment.java enum
    Location: stokr-oms/src/main/java/com/stokr/oms/domain/
    Values:
      - LIVE
      - PAPER
      - SIMULATION
      - REPLAY
```

**Estimated effort:** 4 hours
**Dependencies:** 
- Existing: ExecutionMode.java (similar enum)
- Review: ApplicationEvent pattern (used in codebase)

---

### Phase 1.3: Monitoring Layer

**Objective:** Core scheduler and position loading

**Deliverables:**

```
[ ] Create: PositionMonitoringScheduler.java
    Location: stokr-oms/src/main/java/com/stokr/oms/schedule/
    Methods:
      - @Scheduled monitorOpenPositions()
      - Process user batches
      - Error handling and logging
      - Cycle metrics tracking
    Dependency injection:
      - PositionMonitoringService

[ ] Create: PositionMonitoringService.java (main service)
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - processUserPositions(userId): int (returns exits created)
      - loadOpenPositions(userId): List<PortfolioPosition>
      - loadUserIds(): List<UUID>
      - Private helpers for evaluation and filtering
    Dependencies:
      - PortfolioPositionRepository
      - OmsOrderRepository (for entry orders with target/stop)
      - MarketDataQueryService
      - SessionValidator
      - MarketDataValidator
      - OrderPlacementService
      - ApplicationEventPublisher

[ ] Create: UserBatchProcessor.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Purpose: Sequential or parallel processing of users
    Phase 1: Sequential only
    Phase 2: Can parallelize

[ ] Modify: PortfolioPositionRepository.java
    Location: stokr-oms/src/main/java/com/stokr/oms/repository/
    Add method:
      List<UUID> findUserIdsWithOpenPositions()
      Equivalent SQL:
        SELECT DISTINCT user_id FROM portfolio_positions
        WHERE deleted = FALSE AND quantity != 0
```

**Estimated effort:** 6 hours
**Dependencies:**
- Existing: PortfolioPositionRepository
- Existing: OmsOrderRepository
- Existing: MarketDataQueryService
- To create: SessionValidator, MarketDataValidator

---

### Phase 1.4: Exit Evaluation Layer

**Objective:** Logic to determine if exit conditions are met

**Deliverables:**

```
[ ] Create: TargetHitEvaluator.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Method:
      - evaluateTargetHit(position, entryOrder, currentPrice): ExitDecision?
    Logic:
      - Get entryOrder.targetPrice
      - Long position: currentPrice >= targetPrice
      - Short position: currentPrice <= targetPrice
      - Return ExitDecision if true, null if false

[ ] Create: StopLossEvaluator.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Method:
      - evaluateStopLoss(position, entryOrder, currentPrice): ExitDecision?
    Logic:
      - Get entryOrder.stopPrice
      - Long position: currentPrice <= stopPrice
      - Short position: currentPrice >= stopPrice
      - Return ExitDecision if true, null if false

[ ] Create: ExitEvaluationService.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - evaluatePosition(position, entryOrder, currentPrice): ExitDecision?
      - Tries target hit, then stop loss
      - Returns first ExitDecision found, or null
    Dependencies:
      - TargetHitEvaluator
      - StopLossEvaluator

[ ] Validation: Unit test each evaluator
    File: stokr-oms/src/test/java/com/stokr/oms/service/
```

**Estimated effort:** 4 hours
**Dependencies:**
- Existing: OmsOrder (has target_price, stop_price)
- To create: ExitReason enum

---

### Phase 1.5: OMS Integration

**Objective:** Create exit orders via existing OrderPlacementService

**Deliverables:**

```
[ ] Create: ExitOrderCreationService.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - createExitOrder(userId, exitDecision): OmsOrder
      - Uses existing OrderPlacementService.place()
      - Sets CreateOrderRequest:
        * symbol: from position
        * side: SELL if long, BUY if short
        * orderType: "MARKET"
        * quantity: position.quantity.abs()
        * idempotencyKey: "position-monitor:{positionId}:{cycleNumber}"
        * executionMode: from position environment
        * brokerVendor: "ZERODHA" (LIVE) or "SIM"
        * strategyKey: "POSITION_MONITORING_SERVICE"
      - Returns OmsOrder
    Dependencies:
      - OrderPlacementService (existing)

[ ] Create: ExitOrderTracker.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - recordExitOrderCreated(exitDecision, order): void
      - Updates position with exit_order_id
      - Publish ExitEvent with orderId
    Database update: Update portfolio_positions set exit_order_id = ?

[ ] Verify: OrderPlacementService compatibility
    File: stokr-execution/service/OrderPlacementService.java
    Action: Review idempotencyKey handling, ensure works with "position-monitor" prefix
```

**Estimated effort:** 3 hours
**Dependencies:**
- Existing: OrderPlacementService
- Existing: CreateOrderRequest
- To create: ExitOrderTracker

---

### Phase 1.6: Duplicate Protection

**Objective:** Prevent creating multiple exit orders for same position

**Deliverables:**

```
[ ] Create: DuplicateExitChecker.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - hasRecentExitOrder(userId, symbol, windowSeconds): boolean
    Logic:
      SELECT COUNT(*) FROM oms_orders
      WHERE user_id = ? AND symbol = ? 
      AND deleted = FALSE
      AND order_reason = 'POSITION_MONITORING_SERVICE'
      AND created_at > NOW() - INTERVAL '300 seconds'
      AND state NOT IN ('REJECTED', 'CANCELLED', 'FILLED')
      
      Return COUNT > 0

[ ] Add database index
    File: SQL migration
    Content:
      CREATE INDEX idx_exit_order_check 
      ON oms_orders(user_id, symbol, created_at)
      WHERE deleted = FALSE
      AND order_reason = 'POSITION_MONITORING_SERVICE'
      AND state NOT IN ('REJECTED', 'CANCELLED', 'FILLED')

[ ] Modify: PositionMonitoringService.evaluatePosition()
    Add check before creating exit:
      if (duplicateChecker.hasRecentExitOrder(userId, symbol, 300)) {
          log.debug("Exit order already exists, skipping");
          return null;
      }

[ ] Idempotency key design
    Format: "position-monitor:{positionId}:{cycleNumber}"
    Example: "position-monitor:a1b2c3d4-e5f6-47a8-b9c0-d1e2f3a4b5c6:123"
    Ensures same key in same cycle window → same order returned
```

**Estimated effort:** 2 hours
**Dependencies:**
- Existing: OmsOrderRepository
- To create: DuplicateExitChecker

---

### Phase 1.7: Audit Layer

**Objective:** Record all exit decisions for compliance and debugging

**Deliverables:**

```
[ ] Create: position_exit_audit table
    File: SQL migration (V001_AddPositionExitAudit.sql)
    Columns:
      - id: SERIAL PRIMARY KEY
      - timestamp: TIMESTAMP DEFAULT NOW()
      - user_id: UUID NOT NULL
      - position_id: UUID NOT NULL
      - symbol: VARCHAR(20) NOT NULL
      - entry_price: DECIMAL(15,4) NOT NULL
      - current_price: DECIMAL(15,4) NOT NULL
      - exit_reason: VARCHAR(50) NOT NULL
      - environment: VARCHAR(20) NOT NULL (LIVE/PAPER/etc)
      - exit_order_id: UUID
      - strategy_name: VARCHAR(100)
      - realized_pnl: DECIMAL(15,2)
      - created_by_service: VARCHAR(100) DEFAULT 'POSITION_MONITORING_SERVICE'
    Indexes:
      - INDEX idx_user_time (user_id, timestamp DESC)
      - INDEX idx_symbol_time (symbol, timestamp DESC)
      - INDEX idx_reason (exit_reason)

[ ] Create: PositionExitAudit.java entity
    Location: stokr-oms/src/main/java/com/stokr/oms/domain/
    Maps to position_exit_audit table
    Fields match table above

[ ] Create: PositionExitAuditRepository.java
    Location: stokr-oms/src/main/java/com/stokr/oms/repository/
    Methods:
      - save(PositionExitAudit): PositionExitAudit
      - findByUserId(UUID): List<PositionExitAudit>

[ ] Create: ExitAuditService.java
    Location: stokr-oms/src/main/java/com/stokr/oms/service/
    Methods:
      - recordExitDecision(exitEvent): void
      - Inserts into position_exit_audit
      - Called by event listener

[ ] Create: PositionExitEventListener.java
    Location: stokr-oms/src/main/java/com/stokr/oms/event/
    @EventListener on ExitEvent
    Call ExitAuditService.recordExitDecision()

[ ] Create: position_exit_events table (optional event log)
    Immutable event log for detailed audit trail
    Columns: id, event_type, event_data (JSON), created_at
```

**Estimated effort:** 4 hours
**Dependencies:**
- Existing: ExitEvent (to create in Phase 1.2)
- Existing: Spring event listener pattern

---

### Phase 1.8: Monitoring & Metrics

**Objective:** Track performance and health of monitoring service

**Deliverables:**

```
[ ] Create: PositionMonitoringMetrics.java
    Location: stokr-oms/src/main/java/com/stokr/oms/metrics/
    Metrics:
      - Counter: position_monitoring_cycles_total
      - Counter: position_monitoring_exits_created
      - Gauge: position_monitoring_cycle_duration_seconds
      - Gauge: position_monitoring_positions_evaluated
      - Counter: position_monitoring_errors_total
      - Counter: position_monitoring_duplicate_attempts
      - Histogram: position_monitoring_latency_millis

[ ] Create: HealthIndicator.java
    Location: stokr-oms/src/main/java/com/stokr/oms/health/
    Name: positionMonitoring
    Status: UP if monitoring enabled and running
    Status: DOWN if disabled or errors

[ ] Add: Prometheus metrics endpoint (already in place)
    Endpoint: /metrics
    Scrape metrics for dashboard

[ ] Create: Monitoring Dashboard (Grafana)
    File: docs/MONITORING_DASHBOARD.json
    Panels:
      - Monitoring active (yes/no)
      - Exits created per hour
      - Cycle duration
      - Errors per hour
      - Environment distribution (LIVE/PAPER/SIMULATION)
      - Exit reason distribution (TARGET_HIT / STOP_LOSS_HIT)
```

**Estimated effort:** 3 hours
**Dependencies:**
- Existing: Micrometer (Spring Boot metrics)
- Existing: Actuator endpoints

---

### Phase 1.9: Testing

**Objective:** Unit and integration tests for all components

**Deliverables:**

```
[ ] Create: TargetHitEvaluatorTest.java
    Tests:
      - Target hit for long position
      - Target not hit for long position
      - Target hit for short position
      - Target not hit for short position
      - Null target price
      - Null current price

[ ] Create: StopLossEvaluatorTest.java
    Tests:
      - Stop loss hit for long position
      - Stop loss not hit for long position
      - Stop loss hit for short position
      - Stop loss not hit for short position
      - Null stop price

[ ] Create: DuplicateExitCheckerTest.java
    Tests:
      - No recent exit (returns false)
      - Recent exit exists (returns true)
      - Old exit ignored (> 300 seconds)
      - Multiple exits same symbol

[ ] Create: PositionMonitoringServiceTest.java
    Integration tests:
      - Load open positions
      - Evaluate positions
      - Create exit orders
      - Update position with order ID
      - Publish events

[ ] Create: ExitOrderCreationServiceTest.java
    Tests:
      - Create MARKET order
      - Correct side (SELL for long, BUY for short)
      - Idempotency key format
      - Broker vendor selection
      - Environment isolation

[ ] Create: EndToEndTest.java
    Scenario:
      - Create position (long 100 shares at 100)
      - Set target 110, stop 90
      - Current price moves to 111
      - Monitoring detects target hit
      - Exit order created
      - Order transitions through states
      - Position quantity becomes 0

[ ] Create: DuplicatePreventionTest.java
    Scenarios:
      - Same exit evaluated twice in same cycle
      - First creates order, second detects duplicate
      - Verify only 1 order created

[ ] Create: FailureSimulationTests.java
    Scenarios:
      - Market data unavailable
      - OMS unavailable
      - Database unavailable
      - Application restart

Location: stokr-oms/src/test/java/com/stokr/oms/
```

**Estimated effort:** 8 hours
**Dependencies:**
- Existing: JUnit 5, Mockito
- Existing: @DataJpaTest, @SpringBootTest

---

### Phase 1.10: Production Rollout

**Objective:** Phased rollout with safety checks

**Deliverables:**

```
[ ] Create: RolloutPlan.md
    Location: docs/
    Content:
      - Phased deployment (stages 1-6 as in design doc)
      - Feature flag settings per stage
      - Success criteria for each stage
      - Rollback procedure (< 30 seconds)

[ ] Create: MonitoringChecklist.md
    Location: docs/
    Content:
      - Metrics to watch during rollout
      - Alert thresholds
      - Dashboard queries
      - Health check endpoints

[ ] Create: DeploymentValidation.md
    Location: docs/
    Checklist:
      - Database migrations applied
      - New classes compiled
      - Feature flag disabled by default
      - Configuration loaded
      - Metrics endpoint working
      - Health check passing
      - Logs accessible

[ ] Create: RollbackProcedure.md
    Location: docs/
    Step-by-step:
      - Disable feature flag
      - Verify no new orders created
      - Check existing orders progress
      - Assess impact
      - Report

[ ] Database migration scripts
    Files:
      - V001_AddPositionExitAudit.sql (create audit table)
      - V002_AddExitFieldsToPosition.sql (optional: add exit tracking fields)
      - V003_CreateExitIndexes.sql (indexes for performance)

[ ] Kubernetes deployment manifest (if applicable)
    File: k8s/position-monitoring-deployment.yaml
    Includes:
      - Feature flag as ConfigMap
      - Environment variables
      - Resource limits
      - Health probes
```

**Estimated effort:** 5 hours
**Dependencies:**
- Completed Phase 1.1-1.9

---

## PART 3: DEPENDENCY MAP

### Build Order (Critical Path)

```
1. Phase 1.1: Configuration Layer
   ↓
2. Phase 1.2: Domain Layer
   ↓
3. Phase 1.3: Monitoring Layer
   ├─ Phase 1.4: Exit Evaluation Layer (can be parallel)
   ├─ Phase 1.5: OMS Integration (can be parallel)
   └─ Phase 1.6: Duplicate Protection (can be parallel)
   ↓
4. Phase 1.7: Audit Layer
   ↓
5. Phase 1.8: Monitoring & Metrics
   ↓
6. Phase 1.9: Testing
   ↓
7. Phase 1.10: Production Rollout
```

### External Dependencies

**Existing (Reuse):**
- PortfolioPositionRepository
- OmsOrderRepository
- OmsExecutionRepository
- MarketDataQueryService
- OrderPlacementService
- OrderLifecycleService
- ApplicationEventPublisher
- Spring @Scheduled
- Micrometer metrics
- PostgreSQL

**New (Create):**
- PositionMonitoringScheduler
- PositionMonitoringService
- TargetHitEvaluator
- StopLossEvaluator
- ExitEvaluationService
- ExitOrderCreationService
- DuplicateExitChecker
- ExitAuditService
- PositionMonitoringMetrics
- (Enums, models, events)

**To Validate:**
- SessionValidator (check if exists)
- MarketDataValidator (check if exists)
- ExecutionMode enum (should exist)

---

## PART 4: DATABASE CHANGES REQUIRED

### New Tables

```sql
CREATE TABLE position_exit_audit (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    user_id UUID NOT NULL,
    position_id UUID NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    entry_price DECIMAL(15,4) NOT NULL,
    current_price DECIMAL(15,4) NOT NULL,
    exit_reason VARCHAR(50) NOT NULL,
    environment VARCHAR(20) NOT NULL,
    exit_order_id UUID,
    strategy_name VARCHAR(100),
    realized_pnl DECIMAL(15,2),
    created_by_service VARCHAR(100) DEFAULT 'POSITION_MONITORING_SERVICE',
    INDEX idx_user_time (user_id, timestamp DESC),
    INDEX idx_symbol_time (symbol, timestamp DESC),
    INDEX idx_reason (exit_reason)
);

CREATE TABLE position_exit_events (
    id SERIAL PRIMARY KEY,
    event_type VARCHAR(50),
    event_data JSON,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    INDEX idx_type_time (event_type, created_at DESC)
);
```

### Modified Tables (Optional)

```sql
-- Optional: Add fields to track exit state on position itself
ALTER TABLE portfolio_positions ADD COLUMN (
    exit_order_id UUID,
    exit_state VARCHAR(20),  -- OPEN, EXIT_PENDING, CLOSED
    last_exit_attempt_at TIMESTAMP,
    last_exit_reason VARCHAR(50)
);

-- Optional: Add to oms_orders for tracking exit orders
ALTER TABLE oms_orders ADD COLUMN (
    order_reason VARCHAR(100),  -- 'POSITION_MONITORING_SERVICE', 'TERMINAL_FLATTEN', etc.
    exit_trigger_reason VARCHAR(50),  -- TARGET_HIT, STOP_LOSS_HIT
    exit_triggered_at TIMESTAMP
);
```

### New Indexes

```sql
CREATE INDEX idx_exit_order_check 
ON oms_orders(user_id, symbol, created_at)
WHERE deleted = FALSE
AND order_reason = 'POSITION_MONITORING_SERVICE'
AND state NOT IN ('REJECTED', 'CANCELLED', 'FILLED');

CREATE INDEX idx_open_positions
ON portfolio_positions(user_id, symbol)
WHERE deleted = FALSE AND quantity != 0;
```

### Migration Files

```
Migration files location: 
  stokr-oms/src/main/resources/db/migration/

Files:
  V1_001__CreatePositionExitAudit.sql
  V1_002__CreateExitOrderIndexes.sql
  V1_003__OptionalAddExitFieldsToPosition.sql
```

---

## PART 5: CLASS CREATION LIST

### New Classes to Create (23 total)

**Domain Layer (5 classes):**
1. ExitReason.java (enum)
2. ExitState.java (enum)
3. ExitDecision.java (model)
4. ExitEvent.java (domain event)
5. ExecutionEnvironment.java (enum)

**Monitoring Layer (4 classes):**
6. PositionMonitoringScheduler.java
7. PositionMonitoringService.java
8. UserBatchProcessor.java
9. SessionValidator.java (if not exists)

**Evaluation Layer (3 classes):**
10. TargetHitEvaluator.java
11. StopLossEvaluator.java
12. ExitEvaluationService.java

**OMS Integration (3 classes):**
13. ExitOrderCreationService.java
14. ExitOrderTracker.java
15. DuplicateExitChecker.java

**Audit Layer (4 classes):**
16. PositionExitAudit.java (entity)
17. PositionExitAuditRepository.java
18. ExitAuditService.java
19. PositionExitEventListener.java

**Monitoring (2 classes):**
20. PositionMonitoringMetrics.java
21. PositionMonitoringHealthIndicator.java

**Configuration (1 class):**
22. PositionMonitoringConfig.java

**Testing (add to existing structure):**
23. Test classes (see Phase 1.9)

---

## PART 6: CLASS MODIFICATION LIST

### Existing Classes to Modify (5 total)

**1. PortfolioPositionRepository.java**
   - Add method: findUserIdsWithOpenPositions()

**2. OmsOrderRepository.java**
   - Verify: findByUserIdAndIdempotencyKeyAndDeletedFalse() exists
   - Add method (if not exists): countRecentExitOrders()

**3. application.properties**
   - Add 7 new configuration properties

**4. Spring Boot main application class (if needed)**
   - Enable scheduling: @EnableScheduling

**5. EventPublisher/Event configuration (if needed)**
   - Ensure ExitEvent can be published/listened to

---

## PART 7: TESTING CHECKLIST

### Unit Tests (15 test classes)

```
[ ] TargetHitEvaluatorTest - 6 test methods
[ ] StopLossEvaluatorTest - 6 test methods
[ ] ExitEvaluationServiceTest - 5 test methods
[ ] DuplicateExitCheckerTest - 5 test methods
[ ] ExitOrderCreationServiceTest - 6 test methods
[ ] PositionMonitoringServiceTest - 8 test methods
[ ] ExitAuditServiceTest - 4 test methods
[ ] PositionMonitoringMetricsTest - 4 test methods
[ ] ExitReasonEnumTest - 2 test methods
[ ] ExitStateEnumTest - 2 test methods
[ ] ExitDecisionBuilderTest - 4 test methods
[ ] ExecutionEnvironmentTest - 2 test methods
[ ] MarketDataValidatorTest - 5 test methods
[ ] SessionValidatorTest - 5 test methods
[ ] PositionMonitoringSchedulerTest - 6 test methods
```

### Integration Tests (8 test classes)

```
[ ] PositionMonitoringEndToEndTest - Full flow
[ ] DuplicatePreventionIntegrationTest
[ ] FailureRecoveryTest
[ ] MarketDataStaleDetectionTest
[ ] EnvironmentIsolationTest
[ ] AuditTrailIntegrationTest
[ ] EventPublishingIntegrationTest
[ ] MetricsPublishingTest
```

### Manual Testing Checklist

```
Pre-deployment:
[ ] Can manually trigger monitoring via admin endpoint
[ ] Can create test positions
[ ] Can set target/stop prices
[ ] Can move market price to trigger exit
[ ] Can verify exit order created
[ ] Can see audit trail
[ ] Can see metrics

Staging environment:
[ ] Run with 50 test positions
[ ] Monitor for 2+ hours
[ ] Verify no duplicate orders
[ ] Verify environment isolation (LIVE/PAPER)
[ ] Verify metrics accuracy
[ ] Verify audit logging
[ ] Simulate market data staleness
[ ] Simulate OMS unavailability
[ ] Verify graceful recovery
```

---

## PART 8: ROLLOUT CHECKLIST

### Pre-Rollout

```
[ ] All code reviewed and approved
[ ] All tests passing (100% coverage for core logic)
[ ] Database migrations tested on staging
[ ] Performance load tested (100+ positions)
[ ] Monitoring dashboard deployed
[ ] Runbook created and verified
[ ] Team trained on feature
[ ] Rollback procedure tested
```

### Rollout Phase Checklist

```
Phase 1 (Stage 1: 1 test account):
[ ] Feature flag enabled for test user only
[ ] Monitor for 30 minutes
[ ] Verify exits triggering correctly
[ ] Verify no duplicate orders
[ ] Check database queries performance
[ ] Check CPU/memory usage

Phase 2 (Stage 2: 10% of users):
[ ] Gradually enable for 10%
[ ] Monitor for 60 minutes
[ ] Verify no errors in logs
[ ] Verify metrics look healthy
[ ] Check for any edge cases

Phase 3 (Stage 3: 50% of users):
[ ] Enable for 50%
[ ] Monitor for 120 minutes
[ ] Verify performance under load
[ ] Verify exit distribution reasonable

Phase 4 (Stage 4: 100% of users):
[ ] Enable for all users
[ ] Monitor rest of day
[ ] Document results
[ ] Plan any tuning needed
```

### Post-Rollout

```
[ ] Daily monitoring for 1 week
[ ] Weekly metrics review for 1 month
[ ] Collect user feedback
[ ] Identify any issues
[ ] Plan Phase 2 optimizations
```

---

## PART 9: RISK ASSESSMENT

### High Risk (1)

**Risk:** Duplicate exit orders created
- **Impact:** HIGH (multiple sells could over-exit, negative P&L)
- **Probability:** MEDIUM (if duplicate checker fails)
- **Mitigation:** 
  - Triple-check: code, database constraint, idempotency
  - Extensive testing
  - Monitoring alert if duplicates detected
  - Manual audit before full rollout

### Medium Risk (3)

**Risk:** Stale market data used for exit decisions
- **Impact:** MEDIUM (incorrect exits)
- **Probability:** LOW (15-second threshold conservative)
- **Mitigation:** Skip evaluation, log warning, retry next cycle

**Risk:** OMS unavailable, order creation fails
- **Impact:** MEDIUM (missed exits)
- **Probability:** LOW (OMS is critical, well-maintained)
- **Mitigation:** Automatic retry next cycle, alert on pattern

**Risk:** Environment isolation fails (LIVE/PAPER mix)
- **Impact:** HIGH (wrong orders in wrong account)
- **Probability:** LOW (explicit filtering at load time)
- **Mitigation:** Validation checks, unit tests, monitoring

### Low Risk (2)

**Risk:** Scheduler performance degrades with 100+ positions
- **Impact:** LOW (might miss 30-second window)
- **Probability:** LOW (batch processing optimized)
- **Mitigation:** Performance testing, monitoring latency, can parallelize

**Risk:** Audit logging fills database too quickly
- **Impact:** LOW (audit table, not critical path)
- **Probability:** LOW (retention policy can purge old records)
- **Mitigation:** Indexes, retention policy, monitoring size

---

## PART 10: EFFORT ESTIMATE PER PHASE

### Summary

| Phase | Deliverables | Hours | Days | Risk |
|-------|--------------|-------|------|------|
| 1.1 | Configuration | 3 | 0.4 | LOW |
| 1.2 | Domain Layer | 4 | 0.5 | LOW |
| 1.3 | Monitoring | 6 | 0.75 | LOW |
| 1.4 | Evaluation | 4 | 0.5 | LOW |
| 1.5 | OMS Integration | 3 | 0.4 | MEDIUM |
| 1.6 | Duplicate Protection | 2 | 0.25 | MEDIUM |
| 1.7 | Audit Layer | 4 | 0.5 | LOW |
| 1.8 | Metrics | 3 | 0.4 | LOW |
| 1.9 | Testing | 8 | 1.0 | LOW |
| 1.10 | Rollout | 5 | 0.6 | MEDIUM |
| **TOTAL** | | **42** | **5.35** | |

### Timeline

**Week 1:**
- Mon-Tue: Phases 1.1-1.2 (7 hours)
- Wed-Thu: Phases 1.3-1.4 (10 hours)
- Fri: Phase 1.5-1.6 (5 hours)

**Week 2:**
- Mon: Phase 1.7-1.8 (7 hours)
- Tue-Wed: Phase 1.9 Testing (8 hours)
- Thu-Fri: Phase 1.10 Rollout + Staging validation (5 hours)

**Timeline: 5-6 days of development + 1 week staging/rollout = ~2 weeks to production**

---

## CONCLUSION

This implementation plan is based on actual Stokr codebase architecture:

✅ **Validated against real code:**
- target_price/stop_price locations verified
- Repository patterns confirmed
- Event publishing pattern confirmed
- Idempotency mechanism confirmed
- Order creation flow confirmed

✅ **Minimal scope:**
- Only target hit + stop loss (no indicators)
- Reuses existing infrastructure (OrderPlacementService, PortfolioAccountingService)
- No modifications to entry system
- Pure addition to platform

✅ **Production safe:**
- Duplicate prevention (3-layer defense)
- Stale data detection
- Environment isolation
- Complete audit trail
- Comprehensive testing
- Phased rollout with rollback

✅ **Ready to implement:**
- 10 phases with clear deliverables
- 23 new classes identified
- 5 classes to modify
- Database changes documented
- Test checklist provided
- Effort estimated: 42 hours

**Next step:** Approve implementation plan, begin Phase 1.1 (Configuration Layer)

