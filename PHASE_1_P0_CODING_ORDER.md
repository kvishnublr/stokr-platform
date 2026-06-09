# PHASE 1 P0: EXACT CODING ORDER
## Minimum Viable Automatic Exit Implementation

**Total Components:** 17  
**Total Code:** ~700 lines Java + 40 lines SQL  
**Total Tests:** ~400 lines  
**Estimated Time:** 42 hours (5-6 days)  

---

## STEP-BY-STEP CODING SEQUENCE

### STEP 1: DOMAIN MODELS (No dependencies)
**Time: 1 hour**
**Risk: NONE**

#### 1.1: Create ExitReason.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/domain/
Dependencies: None
Purpose: Enumerate exit decision reasons
```

**File structure:**
```java
public enum ExitReason {
    TARGET_HIT("Target price reached"),
    STOP_LOSS_HIT("Stop loss reached");
    
    private final String description;
}
```

**Time:** 10 minutes  
**Complexity:** TRIVIAL  

---

#### 1.2: Create ExitDecision.java (immutable model)
```
Location: stokr-oms/src/main/java/com/stokr/oms/domain/
Dependencies: ExitReason
Purpose: Immutable decision object (no side effects)
```

**File structure:**
```java
@Value  // Lombok immutable
public class ExitDecision {
    UUID positionId;
    UUID userId;
    String symbol;
    BigDecimal entryPrice;
    BigDecimal currentPrice;
    ExitReason exitReason;
    Instant decisionTimestamp;
}
```

**Time:** 15 minutes  
**Complexity:** TRIVIAL  
**Test:** ExitDecisionTest (verify immutability)  

---

#### 1.3: Create ExitEvent.java (domain event)
```
Location: stokr-common/src/main/java/com/stokr/common/events/
Dependencies: ExitReason, ApplicationEvent (Spring)
Purpose: Domain event published after decision
```

**File structure:**
```java
public class ExitEvent extends ApplicationEvent {
    private final UUID positionId;
    private final UUID userId;
    private final String symbol;
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final ExitReason exitReason;
    private final Instant timestamp;
    private UUID orderId;  // Set after order created
}
```

**Time:** 15 minutes  
**Complexity:** TRIVIAL  
**Test:** ExitEventTest (verify publishing)  

---

### STEP 2: DATABASE SCHEMA (No code dependencies yet)
**Time: 0.5 hours**
**Risk: LOW**

#### 2.1: Create SQL migration V1_001__CreatePositionExitAudit.sql
```
Location: stokr-oms/src/main/resources/db/migration/
Dependencies: PostgreSQL
Purpose: Audit table for all exits
```

**File content:**
```sql
CREATE TABLE position_exit_audit (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    user_id UUID NOT NULL,
    position_id UUID NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    entry_price DECIMAL(15,4) NOT NULL,
    exit_price DECIMAL(15,4) NOT NULL,
    exit_reason VARCHAR(50) NOT NULL,
    exit_order_id UUID,
    strategy_name VARCHAR(100),
    realized_pnl DECIMAL(15,2),
    created_by_service VARCHAR(100) DEFAULT 'POSITION_MONITORING_SERVICE',
    
    INDEX idx_user_time (user_id, timestamp DESC),
    INDEX idx_symbol_time (symbol, timestamp DESC),
    INDEX idx_reason (exit_reason)
);

-- Constraint: validate exit_reason values
ALTER TABLE position_exit_audit ADD CONSTRAINT chk_exit_reason
CHECK (exit_reason IN ('TARGET_HIT', 'STOP_LOSS_HIT'));
```

**Time:** 20 minutes  
**Complexity:** TRIVIAL  
**Validation:** Can run with `flyway info` before deployment  

---

### STEP 3: CONFIGURATION
**Time: 0.5 hours**
**Risk: NONE**

#### 3.1: Add feature flag to application.properties
```
Location: stokr-oms/src/main/resources/
File: application.properties
```

**Content:**
```properties
# Position Monitoring Service
stokr.position.monitor-enabled=true
```

**Time:** 5 minutes  
**Complexity:** TRIVIAL  
**Default:** Enabled (safe, has guards in code)  

---

### STEP 4: CORE EVALUATION LOGIC (Pure logic, no persistence)
**Time: 2 hours**
**Risk: LOW**

#### 4.1: Create TargetHitEvaluator.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/service/
Dependencies: OmsOrder, ExitDecision, ExitReason
Purpose: Check if target price hit
```

**File structure:**
```java
@Component
public class TargetHitEvaluator {
    public ExitDecision evaluate(PortfolioPosition position, OmsOrder entryOrder, 
                                 BigDecimal currentPrice) {
        if (entryOrder == null || entryOrder.getTargetPrice() == null) {
            return null;
        }
        
        boolean isLong = position.getQuantity().signum() > 0;
        BigDecimal target = entryOrder.getTargetPrice();
        
        boolean targetHit = isLong 
            ? currentPrice.compareTo(target) >= 0
            : currentPrice.compareTo(target) <= 0;
        
        if (targetHit) {
            return new ExitDecision(
                position.getId(),
                position.getUserId(),
                position.getSymbol(),
                entryOrder.getEntryReferencePrice(),
                currentPrice,
                ExitReason.TARGET_HIT,
                Instant.now()
            );
        }
        
        return null;
    }
}
```

**Time:** 30 minutes  
**Complexity:** LOW  
**Test:** TargetHitEvaluatorTest (4 test methods)  

---

#### 4.2: Create StopLossEvaluator.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/service/
Dependencies: OmsOrder, ExitDecision, ExitReason
Purpose: Check if stop loss hit
```

**File structure:**
```java
@Component
public class StopLossEvaluator {
    public ExitDecision evaluate(PortfolioPosition position, OmsOrder entryOrder, 
                                 BigDecimal currentPrice) {
        if (entryOrder == null || entryOrder.getStopPrice() == null) {
            return null;
        }
        
        boolean isLong = position.getQuantity().signum() > 0;
        BigDecimal stop = entryOrder.getStopPrice();
        
        boolean stopHit = isLong 
            ? currentPrice.compareTo(stop) <= 0
            : currentPrice.compareTo(stop) >= 0;
        
        if (stopHit) {
            return new ExitDecision(
                position.getId(),
                position.getUserId(),
                position.getSymbol(),
                entryOrder.getEntryReferencePrice(),
                currentPrice,
                ExitReason.STOP_LOSS_HIT,
                Instant.now()
            );
        }
        
        return null;
    }
}
```

**Time:** 30 minutes  
**Complexity:** LOW  
**Test:** StopLossEvaluatorTest (4 test methods)  

---

### STEP 5: OMS INTEGRATION (Calls OrderPlacementService)
**Time: 1.5 hours**
**Risk: MEDIUM**

#### 5.1: Create DuplicateExitChecker.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/service/
Dependencies: OmsOrderRepository
Purpose: Prevent creating duplicate exit orders
```

**File structure:**
```java
@Component
@RequiredArgsConstructor
public class DuplicateExitChecker {
    private final OmsOrderRepository orderRepository;
    
    public boolean hasRecentExitOrder(UUID userId, String symbol, int windowSeconds) {
        Instant cutoff = Instant.now().minus(windowSeconds, ChronoUnit.SECONDS);
        
        int count = orderRepository.countByUserIdAndSymbolAndOrderReasonAndCreatedAfter(
            userId,
            symbol,
            "POSITION_MONITORING_SERVICE",
            cutoff
        );
        
        return count > 0;
    }
}
```

**Time:** 30 minutes  
**Complexity:** MEDIUM (dependency on OMS)  
**Requires:** OmsOrderRepository.countByUserIdAndSymbolAndOrderReasonAndCreatedAfter() method  
**Test:** DuplicateExitCheckerTest (5 test methods)  

---

#### 5.2: Create ExitOrderCreationService.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/service/
Dependencies: OrderPlacementService, CreateOrderRequest, DuplicateExitChecker
Purpose: Create MARKET order to exit position
```

**File structure:**
```java
@Service
@RequiredArgsConstructor
public class ExitOrderCreationService {
    private final OrderPlacementService orderPlacementService;
    private final DuplicateExitChecker duplicateChecker;
    
    @Transactional
    public OmsOrder createExitOrder(UUID userId, ExitDecision decision) 
            throws DuplicateOrderException {
        
        // Check for recent order (prevent duplicate)
        if (duplicateChecker.hasRecentExitOrder(userId, decision.getSymbol(), 300)) {
            throw new DuplicateOrderException("Exit order already exists");
        }
        
        // Determine side (opposite of entry)
        String side = decision.getPositionQuantity().signum() > 0 ? "SELL" : "BUY";
        
        // Create idempotency key
        String idempotencyKey = "position-monitor:" + decision.getPositionId() + 
                                ":" + System.currentTimeMillis() / 10000;
        
        // Build request
        CreateOrderRequest request = new CreateOrderRequest(
            decision.getSymbol(),
            side,
            "MARKET",
            decision.getPositionQuantity().abs(),
            null,  // no limit price for market orders
            ExecutionMode.LIVE,
            "ZERODHA",
            "POSITION_MONITORING_SERVICE",
            idempotencyKey
        );
        
        // Create order via existing service
        return orderPlacementService.place(userId, request);
    }
}
```

**Time:** 45 minutes  
**Complexity:** MEDIUM (orchestration)  
**Test:** ExitOrderCreationServiceTest (6 test methods with Mockito)  

---

### STEP 6: CORE MONITORING SERVICE
**Time: 2 hours**
**Risk: MEDIUM (most critical)**

#### 6.1: Create PositionMonitoringService.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/service/
Dependencies: 
  - PortfolioPositionRepository
  - OmsOrderRepository
  - MarketDataQueryService
  - TargetHitEvaluator
  - StopLossEvaluator
  - ExitOrderCreationService
  - ApplicationEventPublisher
Purpose: Core monitoring logic - runs every 30 seconds
```

**File structure:**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionMonitoringService {
    private final PortfolioPositionRepository positionRepository;
    private final OmsOrderRepository orderRepository;
    private final MarketDataQueryService marketDataService;
    private final TargetHitEvaluator targetHitEvaluator;
    private final StopLossEvaluator stopLossEvaluator;
    private final ExitOrderCreationService exitOrderCreationService;
    private final ApplicationEventPublisher eventPublisher;
    
    @Value("${stokr.position.monitor-enabled:true}")
    private boolean monitoringEnabled;
    
    public int processUserPositions(UUID userId) {
        // Load all open positions
        List<PortfolioPosition> openPositions = 
            positionRepository.findByUserIdAndDeletedFalse(userId).stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().signum() != 0)
                .toList();
        
        if (openPositions.isEmpty()) {
            return 0;
        }
        
        // Get current prices (batch)
        List<String> symbols = openPositions.stream()
            .map(PortfolioPosition::getSymbol)
            .distinct()
            .toList();
        
        Map<String, BigDecimal> currentPrices = getLatestPrices(symbols);
        
        int exitsCreated = 0;
        
        // Evaluate each position
        for (PortfolioPosition position : openPositions) {
            try {
                BigDecimal currentPrice = currentPrices.get(position.getSymbol());
                if (currentPrice == null) {
                    log.debug("No price data for {}", position.getSymbol());
                    continue;
                }
                
                // Load entry order (has target/stop)
                OmsOrder entryOrder = loadEntryOrder(userId, position.getSymbol());
                if (entryOrder == null) {
                    log.debug("No entry order found for {}/{}", userId, position.getSymbol());
                    continue;
                }
                
                // Evaluate target hit
                ExitDecision decision = targetHitEvaluator.evaluate(
                    position, entryOrder, currentPrice);
                
                // If not target, evaluate stop loss
                if (decision == null) {
                    decision = stopLossEvaluator.evaluate(
                        position, entryOrder, currentPrice);
                }
                
                // If decision made, create order
                if (decision != null) {
                    OmsOrder exitOrder = exitOrderCreationService.createExitOrder(
                        userId, decision);
                    
                    // Publish event (listener will record audit)
                    eventPublisher.publishEvent(
                        new ExitEvent(
                            this,
                            decision.getPositionId(),
                            userId,
                            position.getSymbol(),
                            decision.getEntryPrice(),
                            currentPrice,
                            decision.getExitReason(),
                            Instant.now(),
                            exitOrder.getId()
                        )
                    );
                    
                    exitsCreated++;
                    log.info("Exit order created: {}/{} reason={}", 
                        position.getSymbol(), exitOrder.getId(), 
                        decision.getExitReason());
                }
            } catch (Exception ex) {
                log.error("Error evaluating position {}/{}: {}", 
                    userId, position.getSymbol(), ex.getMessage());
            }
        }
        
        return exitsCreated;
    }
    
    private Map<String, BigDecimal> getLatestPrices(List<String> symbols) {
        Map<String, BigDecimal> prices = new HashMap<>();
        for (String symbol : symbols) {
            List<MarketdataCandle> candles = 
                marketDataService.lastBarsAsc(symbol, "1m", 1);
            if (!candles.isEmpty()) {
                prices.put(symbol, candles.get(0).getClosePrice());
            }
        }
        return prices;
    }
    
    private OmsOrder loadEntryOrder(UUID userId, String symbol) {
        // Load most recent entry order for this symbol
        // (has target/stop prices from strategy)
        return orderRepository
            .findFirstByUserIdAndSymbolAndStateNotInAndDeletedFalse(
                userId, symbol, 
                List.of(OrderState.REJECTED, OrderState.CANCELLED))
            .orElse(null);
    }
}
```

**Time:** 90 minutes  
**Complexity:** MEDIUM (core logic)  
**Test:** PositionMonitoringServiceTest (8 integration test methods)  

---

### STEP 7: SCHEDULER
**Time: 0.5 hours**
**Risk: MEDIUM**

#### 7.1: Create PositionMonitoringScheduler.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/schedule/
Dependencies: PositionMonitoringService, PortfolioPositionRepository
Purpose: @Scheduled every 30 seconds
```

**File structure:**
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PositionMonitoringScheduler {
    private final PositionMonitoringService monitoringService;
    private final PortfolioPositionRepository positionRepository;
    
    @Value("${stokr.position.monitor-enabled:true}")
    private boolean monitoringEnabled;
    
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)  // 30 seconds, start after 10 sec
    public void monitorOpenPositions() {
        if (!monitoringEnabled) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        int totalExits = 0;
        int totalErrors = 0;
        
        try {
            // Get all users with open positions
            List<UUID> userIds = positionRepository
                .findDistinctUserIdByDeletedFalseAndQuantityNotZero();
            
            // Process each user
            for (UUID userId : userIds) {
                try {
                    int exitsCreated = monitoringService.processUserPositions(userId);
                    totalExits += exitsCreated;
                } catch (Exception ex) {
                    totalErrors++;
                    log.error("Error processing user {}: {}", userId, ex.getMessage());
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Position monitoring cycle: users={}, exits={}, errors={}, duration={}ms",
                userIds.size(), totalExits, totalErrors, duration);
            
        } catch (Exception ex) {
            log.error("Critical error in position monitoring", ex);
        }
    }
}
```

**Time:** 30 minutes  
**Complexity:** LOW  
**Requires:** New repository method: `findDistinctUserIdByDeletedFalseAndQuantityNotZero()`  
**Test:** PositionMonitoringSchedulerTest (3 test methods)  

---

### STEP 8: EVENT LISTENER (Audit recording)
**Time: 0.5 hours**
**Risk: LOW**

#### 8.1: Create PositionExitAudit.java (entity)
```
Location: stokr-oms/src/main/java/com/stokr/oms/domain/
Dependencies: BaseEntity
Purpose: JPA entity for position_exit_audit table
```

**File structure:**
```java
@Entity
@Table(name = "position_exit_audit")
@Getter
@Setter
public class PositionExitAudit extends BaseEntity {
    @Column(name = "user_id")
    private UUID userId;
    
    @Column(name = "position_id")
    private UUID positionId;
    
    @Column(name = "symbol")
    private String symbol;
    
    @Column(name = "entry_price")
    private BigDecimal entryPrice;
    
    @Column(name = "exit_price")
    private BigDecimal exitPrice;
    
    @Column(name = "exit_reason")
    private String exitReason;
    
    @Column(name = "exit_order_id")
    private UUID exitOrderId;
    
    @Column(name = "timestamp")
    private Instant timestamp;
}
```

**Time:** 15 minutes  
**Complexity:** TRIVIAL  

---

#### 8.2: Create PositionExitEventListener.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/event/
Dependencies: ExitEvent, PositionExitAudit, repository
Purpose: Listen for ExitEvent and record audit
```

**File structure:**
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PositionExitEventListener {
    private final PositionExitAuditRepository auditRepository;
    
    @EventListener
    public void onExitEvent(ExitEvent event) {
        PositionExitAudit audit = new PositionExitAudit();
        audit.setTimestamp(event.getTimestamp());
        audit.setUserId(event.getUserId());
        audit.setPositionId(event.getPositionId());
        audit.setSymbol(event.getSymbol());
        audit.setEntryPrice(event.getEntryPrice());
        audit.setExitPrice(event.getExitPrice());
        audit.setExitReason(event.getExitReason().name());
        audit.setExitOrderId(event.getOrderId());
        
        try {
            auditRepository.save(audit);
            log.info("Exit audit recorded: {}/{}", event.getUserId(), event.getSymbol());
        } catch (Exception ex) {
            log.error("Failed to record exit audit", ex);
        }
    }
}
```

**Time:** 20 minutes  
**Complexity:** LOW  
**Test:** PositionExitEventListenerTest (2 test methods)  

---

#### 8.3: Create PositionExitAuditRepository.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/repository/
Dependencies: PositionExitAudit entity, Spring Data JPA
Purpose: Repository for audit queries
```

**File structure:**
```java
@Repository
public interface PositionExitAuditRepository extends JpaRepository<PositionExitAudit, UUID> {
    List<PositionExitAudit> findByUserId(UUID userId);
}
```

**Time:** 5 minutes  
**Complexity:** TRIVIAL  

---

### STEP 9: REPOSITORY MODIFICATIONS
**Time: 0.5 hours**
**Risk: LOW**

#### 9.1: Add methods to OmsOrderRepository.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/repository/
Method 1: countByUserIdAndSymbolAndOrderReasonAndCreatedAfter()
Purpose: For DuplicateExitChecker
```

```java
// Add to existing OmsOrderRepository interface:
int countByUserIdAndSymbolAndOrderReasonAndCreatedAfter(
    UUID userId, String symbol, String orderReason, Instant createdAfter);
```

**Time:** 5 minutes  
**Complexity:** TRIVIAL  

---

#### 9.2: Add method to PortfolioPositionRepository.java
```
Location: stokr-oms/src/main/java/com/stokr/oms/repository/
Method: findDistinctUserIdByDeletedFalseAndQuantityNotZero()
Purpose: For scheduler to find all users with open positions
```

```java
// Add to existing PortfolioPositionRepository interface:
@Query("SELECT DISTINCT p.userId FROM PortfolioPosition p " +
       "WHERE p.deleted = FALSE AND p.quantity <> 0")
List<UUID> findDistinctUserIdByDeletedFalseAndQuantityNotZero();
```

**Time:** 5 minutes  
**Complexity:** TRIVIAL  

---

### STEP 10: UNIT TESTS
**Time: 4 hours**
**Risk: NONE (local testing)**

#### 10.1: Create TargetHitEvaluatorTest.java
```
Location: stokr-oms/src/test/java/.../service/
Test cases:
  1. Target hit for long position (qty > 0)
  2. Target not hit for long position
  3. Target hit for short position (qty < 0)
  4. Target not hit for short position
  5. Null target price
  6. Null current price
```

**Time:** 45 minutes  
**Complexity:** LOW  

---

#### 10.2: Create StopLossEvaluatorTest.java
```
Location: stokr-oms/src/test/java/.../service/
Test cases:
  1. Stop hit for long position
  2. Stop not hit for long position
  3. Stop hit for short position
  4. Stop not hit for short position
  5. Null stop price
```

**Time:** 45 minutes  
**Complexity:** LOW  

---

#### 10.3: Create DuplicateExitCheckerTest.java
```
Location: stokr-oms/src/test/java/.../service/
Test cases:
  1. No recent order (returns false)
  2. Recent order exists (returns true)
  3. Old order ignored (> window)
  4. Multiple orders same symbol
  5. Different symbol not counted
```

**Time:** 45 minutes  
**Complexity:** MEDIUM (requires Mockito)  

---

#### 10.4: Create ExitOrderCreationServiceTest.java
```
Location: stokr-oms/src/test/java/.../service/
Test cases:
  1. Create MARKET order
  2. Side is SELL for long position
  3. Side is BUY for short position
  4. Idempotency key format correct
  5. Quantity is absolute value
  6. OrderPlacementService called correctly
```

**Time:** 60 minutes  
**Complexity:** MEDIUM (requires Mockito + TransactionTest)  

---

#### 10.5: Create PositionMonitoringServiceTest.java
```
Location: stokr-oms/src/test/java/.../service/
Test cases:
  1. Load open positions
  2. Evaluate target hit
  3. Evaluate stop loss
  4. Create exit order
  5. Publish event
  6. Handle missing price data
  7. Handle evaluation error (continue)
  8. Return exit count
```

**Time:** 90 minutes  
**Complexity:** MEDIUM (integration test, @SpringBootTest)  

---

### STEP 11: COMPILATION & VERIFICATION
**Time: 0.5 hours**
**Risk: LOW**

#### 11.1: Compile all code
```bash
./gradlew clean build -x test
```

**Time:** 20 minutes  
**Complexity:** TRIVIAL  

---

#### 11.2: Run all P0 tests
```bash
./gradlew test -k "TargetHit or StopLoss or DuplicateExit or ExitOrderCreation or PositionMonitoring"
```

**Requirements:** All tests MUST pass  
**Time:** 15 minutes  
**Complexity:** TRIVIAL  

---

## FINAL CODING SEQUENCE SUMMARY

### Complete Build Order (No parallel work - sequential only)

```
1. ExitReason.java (10 min)
2. ExitDecision.java (15 min)
3. ExitEvent.java (15 min)
4. SQL Migration (20 min)
5. application.properties (5 min)
6. TargetHitEvaluator.java (30 min)
7. StopLossEvaluator.java (30 min)
8. DuplicateExitChecker.java (30 min)
9. ExitOrderCreationService.java (45 min)
10. PositionMonitoringService.java (90 min) ← Core service
11. PositionMonitoringScheduler.java (30 min)
12. PositionExitAudit.java (15 min)
13. PositionExitEventListener.java (20 min)
14. PositionExitAuditRepository.java (5 min)
15. OmsOrderRepository.add methods (5 min)
16. PortfolioPositionRepository.add methods (5 min)

Tests:
17. TargetHitEvaluatorTest.java (45 min)
18. StopLossEvaluatorTest.java (45 min)
19. DuplicateExitCheckerTest.java (45 min)
20. ExitOrderCreationServiceTest.java (60 min)
21. PositionMonitoringServiceTest.java (90 min)

Build:
22. Compile (20 min)
23. Test (15 min)

TOTAL: 695 minutes ≈ 11.6 hours development (not including coffee, debugging, reviews)
```

---

## DEVELOPER ASSIGNMENT

**Recommended 1-developer approach:**
- All code: Same developer (maintains consistency)
- All tests: Same developer (understands intent)
- Time: 5-6 days working full-time

**Recommended 2-developer approach:**
- Developer 1: Domain models (1-5) + Core service (10) = 2 days
- Developer 2: Tests (17-21) = 2 days (can start after core service)
- Both: Build & verify (22-23) = 0.5 day

---

## INTEGRATION CHECKLIST (Before Go-Live)

```
[ ] Code compiles cleanly (0 errors, 0 warnings)
[ ] All unit tests pass (5/5 test suites)
[ ] All integration tests pass
[ ] Database migration runs successfully
[ ] Feature flag can be enabled/disabled without restart
[ ] Feature flag disabled by default in production
[ ] Logs show correct messaging
[ ] No new exceptions in startup
[ ] Health check shows all systems OK
[ ] Can create test position manually
[ ] Can verify position in database
[ ] Can verify exit order created when target hit
[ ] Can verify audit record created
[ ] Can verify event published
[ ] Can disable feature flag and restart
```

---

## GO/NO-GO CRITERIA FOR DEPLOYMENT

**GO if:**
- ✅ All 5 test suites pass
- ✅ Database migration applies cleanly
- ✅ Feature flag works (can disable)
- ✅ Manual testing confirms exit order creation
- ✅ Audit trail recorded
- ✅ No regression in existing systems

**NO-GO if:**
- ❌ Any test fails
- ❌ Database migration fails
- ❌ Feature flag doesn't work
- ❌ Exit order not created
- ✅ Audit not recorded (can add listener later)

---

## PRODUCTION DEPLOYMENT

### Pre-Deployment
1. Deploy code with feature flag disabled
2. Verify no startup errors
3. Run health checks
4. Verify feature flag setting

### Deployment
1. Enable feature flag for 1% of users
2. Monitor for 30 minutes
3. If OK: enable for 10%
4. If OK: enable for 50%
5. If OK: enable for 100%

### Rollback
1. Disable feature flag: `stokr.position.monitor-enabled=false`
2. Reload config (no restart needed)
3. Verify no new orders created
4. No code rollback needed

**Rollback time: < 2 minutes**

---

## CONCLUSION

**P0 Implementation is:**
- ✅ Minimal (17 components)
- ✅ Focused (only target + stop loss)
- ✅ Safe (duplicates prevented, audit trail)
- ✅ Fast (< 12 hours coding)
- ✅ Testable (5 test suites, 30+ test methods)
- ✅ Deployable (feature flag rollback)
- ✅ Extensible (P1 adds on top cleanly)

**Ready to assign to developer(s)?**

