# 🔴 P0 STABILITY SPRINT — DETAILED IMPLEMENTATION SPECIFICATION
## BROKER TRUTH, POSITION OWNERSHIP, RECONCILIATION, MANUAL EXIT PROTECTION

**Version:** 1.0  
**Status:** READY FOR IMPLEMENTATION  
**Priority:** CRITICAL (P0)  
**Timeline:** 3-5 days (continuous deployment)  
**Risk Level:** MEDIUM (requires live validation)  

---

# PART 1: ARCHITECTURE & DATA MODEL CHANGES

## 1.1 DATABASE SCHEMA EXTENSIONS

### NEW TABLE: position_lifecycle_audit
```sql
CREATE TABLE position_lifecycle_audit (
    id UUID PRIMARY KEY,
    position_id UUID NOT NULL,
    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    
    -- State machine
    old_state VARCHAR(32),
    new_state VARCHAR(32),
    
    -- Ownership tracking
    owner_type VARCHAR(32) NOT NULL,
        -- Values: STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH, SYSTEM
    
    -- Exit source
    exit_source VARCHAR(32),
        -- Values: STRATEGY_SIGNAL, USER_BROKER, USER_TERMINAL, BROKER_LIQUIDATION, RISK_CIRCUIT, KILL_SWITCH, MANUAL_SUPPRESSION
    
    -- Causation
    signal_id UUID,
    order_id UUID,
    execution_id UUID,
    broker_position_id VARCHAR(255),
    
    -- Audit
    triggered_by VARCHAR(32),  -- USER, SYSTEM, BROKER, STRATEGY
    reason TEXT,
    source_system VARCHAR(32),  -- OMS, BROKER, TERMINAL, RISK, RECONCILIATION
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    event_time TIMESTAMP WITH TIME ZONE,
    
    -- Reconciliation
    reconciliation_id UUID,
    
    FOREIGN KEY (position_id) REFERENCES portfolio_positions(id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (signal_id) REFERENCES strategy_signals(id),
    FOREIGN KEY (order_id) REFERENCES oms_orders(id),
    FOREIGN KEY (execution_id) REFERENCES oms_executions(id)
);

CREATE INDEX idx_position_lifecycle_position ON position_lifecycle_audit(position_id);
CREATE INDEX idx_position_lifecycle_user ON position_lifecycle_audit(user_id);
CREATE INDEX idx_position_lifecycle_symbol ON position_lifecycle_audit(symbol);
CREATE INDEX idx_position_lifecycle_exit_source ON position_lifecycle_audit(exit_source);
CREATE INDEX idx_position_lifecycle_owner_type ON position_lifecycle_audit(owner_type);
```

### ALTER TABLE: portfolio_positions (Add ownership tracking)
```sql
ALTER TABLE portfolio_positions ADD COLUMN (
    -- Position state machine
    position_state VARCHAR(32) DEFAULT 'OPEN',
        -- Values: OPEN, CLOSING, CLOSED, ZOMBIE, GHOST
    
    -- Ownership
    owner_type VARCHAR(32),
        -- Values: STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH, NULL
    
    -- Exit tracking
    exit_source VARCHAR(32),
        -- Values: STRATEGY_SIGNAL, MANUAL_BROKER, MANUAL_TERMINAL, BROKER_LIQUIDATION, RISK_CIRCUIT, KILL_SWITCH
    
    -- Causation linkage
    entry_signal_id UUID,
    exit_signal_id UUID,
    entry_order_id UUID,
    exit_order_id UUID,
    entry_execution_id UUID,
    exit_execution_id UUID,
    
    -- Manual suppression
    manual_suppression_active BOOLEAN DEFAULT FALSE,
    suppression_reason VARCHAR(255),
    suppressed_until TIMESTAMP WITH TIME ZONE,
    
    -- Lifecycle timestamps
    position_opened_at TIMESTAMP WITH TIME ZONE,
    position_closed_at TIMESTAMP WITH TIME ZONE,
    position_state_updated_at TIMESTAMP WITH TIME ZONE,
    
    -- Reconciliation state
    last_reconciliation_at TIMESTAMP WITH TIME ZONE,
    reconciliation_status VARCHAR(32),
        -- Values: SYNCED, PENDING, DIVERGED, GHOST, ORPHAN
    
    -- Broker linkage
    broker_position_id VARCHAR(255),
    broker_order_id VARCHAR(255)
);

CREATE INDEX idx_portfolio_position_state ON portfolio_positions(position_state);
CREATE INDEX idx_portfolio_owner_type ON portfolio_positions(owner_type);
CREATE INDEX idx_portfolio_exit_source ON portfolio_positions(exit_source);
CREATE INDEX idx_portfolio_manual_suppression ON portfolio_positions(manual_suppression_active);
CREATE INDEX idx_portfolio_entry_signal ON portfolio_positions(entry_signal_id);
CREATE INDEX idx_portfolio_exit_signal ON portfolio_positions(exit_signal_id);
```

### ALTER TABLE: oms_orders (Add signal linkage)
```sql
ALTER TABLE oms_orders ADD COLUMN (
    -- Causation linkage
    signal_id UUID UNIQUE,
        -- REQUIRED for LIVE fills
    
    execution_mode_confirmed VARCHAR(32),
        -- Values: LIVE, PAPER, SIMULATION (must match execution mode)
    
    -- Broker reconciliation
    broker_order_id VARCHAR(255) UNIQUE,
    broker_order_status VARCHAR(32),
    broker_rejection_reason TEXT,
    
    -- Duplicate detection
    duplicate_of_order_id UUID,
    is_duplicate BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_orders_signal ON oms_orders(signal_id);
CREATE INDEX idx_orders_broker_id ON oms_orders(broker_order_id);
CREATE INDEX idx_orders_is_duplicate ON oms_orders(is_duplicate);
```

### ALTER TABLE: oms_executions (Add audit trail)
```sql
ALTER TABLE oms_executions ADD COLUMN (
    -- Causation
    signal_id UUID,
    position_id UUID,
    
    -- Reconciliation
    is_synthetic BOOLEAN DEFAULT FALSE,
        -- TRUE = created by reconciliation engine (broker exit detected)
    
    synthetic_reason VARCHAR(255),
        -- "External broker exit detected"
    
    -- Validation
    execution_owner VARCHAR(32),
        -- Values: BROKER, SYSTEM_SYNTHETIC, RECONCILIATION
    
    validation_status VARCHAR(32)
        -- Values: VALIDATED, SYNTHETIC, PENDING_VALIDATION
);

CREATE INDEX idx_executions_signal ON oms_executions(signal_id);
CREATE INDEX idx_executions_position ON oms_executions(position_id);
CREATE INDEX idx_executions_is_synthetic ON oms_executions(is_synthetic);
```

### NEW TABLE: strategy_pause_state
```sql
CREATE TABLE strategy_pause_state (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,
    
    -- Pause state
    current_state VARCHAR(32) NOT NULL,
        -- Values: RUNNING, PAUSED, STOPPED, EXIT_ALL_PAUSED, KILLSWITCH_PAUSED
    
    -- Reason for pause
    pause_reason VARCHAR(255),
    
    -- Who initiated
    triggered_by VARCHAR(32),  -- USER, SYSTEM, KILLSWITCH, MANUAL_EXIT
    triggered_by_id UUID,
    
    -- When to resume
    resume_at TIMESTAMP WITH TIME ZONE,
    resume_condition VARCHAR(255),
        -- Examples: "MANUAL", "NEXT_SESSION", "EXPLICIT_RESUME_CALL"
    
    -- Timestamps
    paused_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resumed_at TIMESTAMP WITH TIME ZONE,
    
    -- Persistence (survives restart)
    survives_restart BOOLEAN DEFAULT TRUE,
    survives_deployment BOOLEAN DEFAULT TRUE,
    
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE(user_id, strategy_name)
);

CREATE INDEX idx_pause_state_user ON strategy_pause_state(user_id);
CREATE INDEX idx_pause_state_strategy ON strategy_pause_state(strategy_name);
CREATE INDEX idx_pause_state_current ON strategy_pause_state(current_state);
```

### NEW TABLE: manual_exit_suppression
```sql
CREATE TABLE manual_exit_suppression (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    position_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    
    -- What to suppress
    suppress_sl_exit BOOLEAN DEFAULT TRUE,
    suppress_target_exit BOOLEAN DEFAULT TRUE,
    suppress_pressure_exit BOOLEAN DEFAULT TRUE,
    suppress_feed_protection_exit BOOLEAN DEFAULT TRUE,
    suppress_auto_exit BOOLEAN DEFAULT TRUE,
    suppress_all_exits BOOLEAN DEFAULT TRUE,
    
    -- Why suppressed
    suppression_reason VARCHAR(255),
        -- "User manually exited from broker"
    
    -- Manual exit details
    manual_exit_source VARCHAR(32),
        -- Values: ZERODHA_APP, KITE_WEB, BROKER_API, TRADER_TERMINAL, BROKER_LIQUIDATION
    
    manual_exit_time TIMESTAMP WITH TIME ZONE,
    manual_exit_quantity NUMERIC(24, 8),
    manual_exit_price NUMERIC(24, 2),
    
    -- Duration
    suppression_starts_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    suppression_expires_at TIMESTAMP WITH TIME ZONE,
        -- NULL = never expires in this session
    
    -- Validation
    suppression_active BOOLEAN DEFAULT TRUE,
    
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (position_id) REFERENCES portfolio_positions(id),
    UNIQUE(position_id)
);

CREATE INDEX idx_suppression_user ON manual_exit_suppression(user_id);
CREATE INDEX idx_suppression_symbol ON manual_exit_suppression(symbol);
CREATE INDEX idx_suppression_active ON manual_exit_suppression(suppression_active);
```

### NEW TABLE: broker_reconciliation_event
```sql
CREATE TABLE broker_reconciliation_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    reconciliation_cycle_id UUID,
    
    -- What happened
    event_type VARCHAR(32) NOT NULL,
        -- Values: BROKER_POSITION_CLOSED, BROKER_POSITION_OPENED, QUANTITY_MISMATCH, ORPHAN_DETECTED, GHOST_DETECTED
    
    symbol VARCHAR(64) NOT NULL,
    
    -- Before/after
    broker_quantity NUMERIC(24, 8),
    oms_quantity NUMERIC(24, 8),
    quantity_mismatch NUMERIC(24, 8),
    
    broker_position_id VARCHAR(255),
    oms_position_id UUID,
    
    -- Resolution
    resolution_action VARCHAR(32),
        -- Values: SYNTHETIC_EXIT_CREATED, POSITION_UPDATED, LIQUIDATION_INITIATED, GHOST_REMOVED
    
    resolution_status VARCHAR(32),
        -- Values: PENDING, RESOLVED, FAILED
    
    -- Audit
    detected_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resolved_at TIMESTAMP WITH TIME ZONE,
    
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_reconciliation_event_user ON broker_reconciliation_event(user_id);
CREATE INDEX idx_reconciliation_event_type ON broker_reconciliation_event(event_type);
CREATE INDEX idx_reconciliation_event_symbol ON broker_reconciliation_event(symbol);
CREATE INDEX idx_reconciliation_event_status ON broker_reconciliation_event(resolution_status);
```

---

## 1.2 CORE STATE MACHINE DEFINITIONS

### Position Lifecycle State Machine
```
OPEN
  ├─ → CLOSING (exit signal generated or manual exit initiated)
  │    ├─ → CLOSED (execution completed)
  │    │    └─ → ZOMBIE (reconciliation can't find position)
  │    └─ → CLOSED_BY_USER (manual exit at broker)
  │         └─ → MANUAL_SUPPRESSION_ACTIVE (future exits suppressed)
  └─ → GHOST (system shows but broker doesn't)
       └─ → GHOST_REMOVED (reconciliation cleanup)

Final States: CLOSED, ZOMBIE, GHOST_REMOVED
```

### Strategy Pause State Machine
```
RUNNING
  ├─ → PAUSED (manual pause)
  │    └─ → RUNNING (manual resume)
  ├─ → EXIT_ALL_PAUSED (user exit all)
  │    └─ NO RESUME (persists session)
  ├─ → KILLSWITCH_PAUSED (risk killswitch)
  │    └─ NO RESUME (persists session)
  └─ → STOPPED (manual stop)
       └─ RUNNING (must explicitly resume)

Persistence: All survive restart, deployment, reconciliation
```

---

# PART 2: WORKSTREAM DETAILED SPECIFICATIONS

## WORKSTREAM 1: LIVE FILL OWNERSHIP & SIGNAL LINKAGE

### 1.1 Requirement
Every LIVE fill MUST contain signal_id linkage. Reject any orphan executions.

### 1.2 Files to Create/Modify

#### NEW: stokr-oms/src/main/java/com/stokr/oms/execution/OmsExecutionSignalValidator.java
```java
/**
 * Validates that LIVE executions have proper signal linkage.
 * - Every LIVE fill must have signal_id
 * - Every signal_id must exist and be valid
 * - Reject orphan executions
 * - Generate audit logs for missing linkage
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmsExecutionSignalValidator {
    
    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository orderRepository;
    private final OmsExecutionRepository executionRepository;
    private final PositionLifecycleAuditRepository auditRepository;
    
    /**
     * VALIDATION RULE: LIVE fills must have signal_id
     */
    @Transactional
    public ValidationResult validateExecutionSignalLinkage(OmsExecution execution) {
        // If LIVE fill, signal_id is REQUIRED
        if (execution.getOrder().getExecutionMode() == ExecutionMode.LIVE) {
            if (execution.getOrder().getSignalId() == null) {
                logOrphanExecution(execution);
                return ValidationResult.REJECT("LIVE fill missing signal_id linkage");
            }
            
            // Verify signal exists
            StrategySignal signal = signalRepository.findById(
                execution.getOrder().getSignalId()
            ).orElseThrow(() -> new NotFoundException("Signal not found"));
            
            // Verify signal validity
            if (signal.getSignalType() != SignalType.BUY && 
                signal.getSignalType() != SignalType.SELL) {
                return ValidationResult.REJECT("Invalid signal type for execution: " + signal.getSignalType());
            }
            
            // Link execution to signal
            execution.setSignalId(signal.getId());
            execution.setValidationStatus("VALIDATED");
            
            log.warn("execution.signal_linkage_validated executionId={} signalId={}", 
                execution.getId(), signal.getId());
            
            return ValidationResult.ACCEPT();
        }
        
        // Paper/simulation fills can be missing signal_id
        return ValidationResult.ACCEPT();
    }
    
    private void logOrphanExecution(OmsExecution execution) {
        log.error("execution.orphan_detected executionId={} orderId={} symbol={} side={}", 
            execution.getId(), 
            execution.getOrder().getId(),
            execution.getOrder().getSymbol(),
            execution.getOrder().getSide());
        
        // Create audit record
        auditRepository.save(PositionLifecycleAudit.builder()
            .executionId(execution.getId())
            .source_system("OMS")
            .reason("Orphan LIVE execution - missing signal linkage")
            .triggered_by("SYSTEM")
            .build());
    }
}
```

#### MODIFY: stokr-oms/src/main/java/com/stokr/oms/service/OrderLifecycleService.java
```java
/**
 * Before persisting execution, validate signal linkage
 */
@Transactional
public OmsExecution persistExecution(OmsExecution execution) {
    // NEW: Validate signal linkage
    ValidationResult validation = executionSignalValidator.validateExecutionSignalLinkage(execution);
    if (!validation.isAccepted()) {
        log.error("execution.rejected reason={}", validation.getReason());
        throw new ExecutionRejectedException(validation.getReason());
    }
    
    // Existing logic...
    return executionRepository.save(execution);
}
```

### 1.3 Validation Tests
```java
@Test
void testLiveExecutionMustHaveSignalId() {
    // LIVE execution without signal_id = REJECTED
    OmsExecution execution = new OmsExecution();
    execution.getOrder().setExecutionMode(ExecutionMode.LIVE);
    execution.getOrder().setSignalId(null);  // MISSING
    
    ValidationResult result = validator.validateExecutionSignalLinkage(execution);
    
    assertThat(result.isAccepted()).isFalse();
    assertThat(result.getReason()).contains("signal_id");
}

@Test
void testAllLiveExecutionsContainSignalId() {
    List<OmsExecution> liveExecutions = executionRepository.findAllLive();
    
    for (OmsExecution exec : liveExecutions) {
        assertThat(exec.getOrder().getSignalId())
            .as("LIVE execution %s missing signal_id", exec.getId())
            .isNotNull();
    }
}

@Test
void testOrphanExecutionAuditLogged() {
    OmsExecution orphan = createLiveExecutionWithoutSignal();
    
    // Should log to position_lifecycle_audit
    List<PositionLifecycleAudit> audits = auditRepository
        .findByExecutionId(orphan.getId());
    
    assertThat(audits).isNotEmpty();
    assertThat(audits.get(0).getReason()).contains("Orphan");
}
```

### 1.4 Success Criteria
- ✅ 100% of LIVE fills contain signal_id
- ✅ 0 orphan executions in audit trail
- ✅ Execution rejected before persistence if signal missing
- ✅ Audit log entries for all linkage failures
- ✅ Dashboard alert on first orphan detection

---

## WORKSTREAM 2: EXTERNAL BROKER EXIT RECONCILIATION

### 2.1 Requirement
When broker position closes (user exits from Zerodha), system must:
1. Detect the closure
2. Create synthetic EXIT execution
3. Update OMS quantity to zero
4. Update portfolio
5. Suppress future exits
6. Mark as CLOSED_BY_USER

### 2.2 Files to Create/Modify

#### NEW: stokr-execution/src/main/java/com/stokr/execution/reconciliation/ExternalBrokerExitHandler.java
```java
/**
 * Handles external broker position closures (user exits from Zerodha).
 * 
 * Flow:
 * 1. Broker has 0, OMS has open → Broker is truth
 * 2. Create synthetic EXIT execution
 * 3. Update OMS to closed state
 * 4. Suppress future exits
 * 5. Update portfolio
 * 6. Update terminal
 * 7. Audit trail
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ExternalBrokerExitHandler {
    
    private final PortfolioPositionRepository positionRepository;
    private final OmsExecutionRepository executionRepository;
    private final OmsOrderRepository orderRepository;
    private final ManualExitSuppressionRepository suppressionRepository;
    private final PositionLifecycleAuditRepository auditRepository;
    private final BrokerReconciliationEventRepository reconciliationEventRepository;
    
    /**
     * CORE LOGIC: Handle broker-initiated position closure
     */
    @Transactional
    public void handleBrokerPositionClosure(
        UUID userId, 
        String symbol, 
        BigDecimal brokerQuantity,  // Should be 0
        BigDecimal omsQuantity       // Should be non-zero
    ) {
        // Step 1: Verify mismatch
        if (brokerQuantity.compareTo(BigDecimal.ZERO) != 0) {
            log.warn("reconciliation.invalid_call broker_qty={} expected=0", brokerQuantity);
            return;
        }
        
        if (omsQuantity.compareTo(BigDecimal.ZERO) == 0) {
            log.info("reconciliation.already_synced symbol={}", symbol);
            return;
        }
        
        // Step 2: Find open position
        PortfolioPosition position = positionRepository
            .findByUserIdAndSymbolAndDeletedFalse(userId, symbol)
            .orElseThrow(() -> new NotFoundException("Position not found"));
        
        // Step 3: Create synthetic EXIT execution
        OmsExecution syntheticExit = createSyntheticExecution(position);
        executionRepository.save(syntheticExit);
        
        log.warn("reconciliation.synthetic_exit_created position={} symbol={} quantity={} syntheticExecution={}",
            position.getId(), symbol, omsQuantity, syntheticExit.getId());
        
        // Step 4: Close OMS position
        position.setQuantity(BigDecimal.ZERO);
        position.setExitExecutionId(syntheticExit.getId());
        position.setExitSource("BROKER_LIQUIDATION");
        position.setPositionState("CLOSED");
        position.setPositionClosedAt(Instant.now());
        positionRepository.save(position);
        
        // Step 5: Create manual exit suppression
        createManualExitSuppression(position, syntheticExit);
        
        // Step 6: Create audit trail
        createAuditEntry(position, syntheticExit, "External broker exit detected");
        
        // Step 7: Record reconciliation event
        recordReconciliationEvent(userId, symbol, position, syntheticExit);
        
        log.warn("reconciliation.broker_exit_handled symbol={} position={} oms_now_closed",
            symbol, position.getId());
    }
    
    private OmsExecution createSyntheticExecution(PortfolioPosition position) {
        OmsExecution execution = new OmsExecution();
        execution.setId(UUID.randomUUID());
        
        // Mark as synthetic
        execution.setIsSynthetic(true);
        execution.setSyntheticReason("External broker exit detected");
        execution.setExecutionOwner("RECONCILIATION");
        execution.setValidationStatus("SYNTHETIC");
        
        // Use last known price or current price
        execution.setAvgPrice(position.getAvgPrice());
        execution.setFilledQty(position.getQuantity().abs());
        
        // Link to position
        execution.setPositionId(position.getId());
        
        // Timestamps
        execution.setExecutionTimestamp(Instant.now());
        execution.setCreatedAt(Instant.now());
        
        return execution;
    }
    
    private void createManualExitSuppression(PortfolioPosition position, OmsExecution exit) {
        ManualExitSuppression suppression = ManualExitSuppression.builder()
            .userId(position.getUserId())
            .positionId(position.getId())
            .symbol(position.getSymbol())
            
            .suppressSlExit(true)
            .suppressTargetExit(true)
            .suppressPressureExit(true)
            .suppressAllExits(true)
            
            .suppressionReason("User manually exited from broker")
            .manualExitSource("EXTERNAL_BROKER")
            .manualExitTime(exit.getExecutionTimestamp())
            .manualExitQuantity(exit.getFilledQty())
            .manualExitPrice(exit.getAvgPrice())
            
            .suppressionActive(true)
            .build();
        
        suppressionRepository.save(suppression);
        
        log.warn("reconciliation.manual_suppression_created position={} symbol={}",
            position.getId(), position.getSymbol());
    }
    
    private void createAuditEntry(PortfolioPosition position, OmsExecution exit, String reason) {
        PositionLifecycleAudit audit = PositionLifecycleAudit.builder()
            .positionId(position.getId())
            .userId(position.getUserId())
            .symbol(position.getSymbol())
            
            .oldState("OPEN")
            .newState("CLOSED")
            
            .ownerType("BROKER")
            .exitSource("BROKER_LIQUIDATION")
            
            .executionId(exit.getId())
            .triggeredBy("SYSTEM")
            .reason(reason)
            .sourceSystem("RECONCILIATION")
            
            .eventTime(Instant.now())
            .build();
        
        auditRepository.save(audit);
    }
    
    private void recordReconciliationEvent(UUID userId, String symbol, PortfolioPosition position, OmsExecution exit) {
        BrokerReconciliationEvent event = BrokerReconciliationEvent.builder()
            .userId(userId)
            .eventType("BROKER_POSITION_CLOSED")
            .symbol(symbol)
            
            .brokerQuantity(BigDecimal.ZERO)
            .omsQuantity(position.getQuantity().abs())
            .quantityMismatch(position.getQuantity().abs())
            
            .brokerPositionId(position.getBrokerPositionId())
            .omsPositionId(position.getId())
            
            .resolutionAction("SYNTHETIC_EXIT_CREATED")
            .resolutionStatus("RESOLVED")
            
            .resolvedAt(Instant.now())
            .build();
        
        reconciliationEventRepository.save(event);
    }
}
```

#### MODIFY: stokr-oms/src/main/java/com/stokr/oms/reconciliation/BrokerReconciliationService.java
```java
/**
 * During reconciliation cycle, detect broker closures and handle them
 */
@Transactional
public void reconcilePositions(UUID userId) {
    List<PortfolioPosition> omsPositions = positionRepository.findOpenPositions(userId);
    Map<String, BigDecimal> brokerPositions = getBrokerPositions(userId);
    
    for (PortfolioPosition omsPos : omsPositions) {
        BigDecimal brokerQty = brokerPositions.getOrDefault(omsPos.getSymbol(), BigDecimal.ZERO);
        
        // NEW: Detect broker closure
        if (brokerQty.compareTo(BigDecimal.ZERO) == 0 && 
            omsPos.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
            
            log.warn("reconciliation.broker_closure_detected symbol={} broker=0 oms={}",
                omsPos.getSymbol(), omsPos.getQuantity());
            
            // Handle the external exit
            brokerExitHandler.handleBrokerPositionClosure(
                userId, 
                omsPos.getSymbol(), 
                BigDecimal.ZERO, 
                omsPos.getQuantity()
            );
        }
    }
}
```

### 2.3 Validation Tests
```java
@Test
@Transactional
void testBrokerClosureDetectedAndHandled() {
    // Setup: Position open in OMS, closed in broker
    PortfolioPosition position = createOpenPosition("INFY", BigDecimal.ONE);
    
    // Simulate broker closure
    brokerStub.setPositionQuantity("INFY", BigDecimal.ZERO);
    
    // Run reconciliation
    brokerReconciliationService.reconcilePositions(userId);
    
    // Verify: Position is now closed in OMS
    PortfolioPosition updated = positionRepository.findById(position.getId()).get();
    assertThat(updated.getQuantity()).isZero();
    assertThat(updated.getPositionState()).isEqualTo("CLOSED");
    assertThat(updated.getExitSource()).isEqualTo("BROKER_LIQUIDATION");
    
    // Verify: Synthetic execution created
    List<OmsExecution> executions = executionRepository.findByPositionId(position.getId());
    assertThat(executions).anySatisfy(e -> {
        assertThat(e.getIsSynthetic()).isTrue();
        assertThat(e.getSyntheticReason()).contains("External broker exit");
    });
    
    // Verify: Manual suppression active
    ManualExitSuppression suppression = suppressionRepository.findByPositionId(position.getId()).get();
    assertThat(suppression.isSuppressionActive()).isTrue();
}

@Test
@Transactional
void testMultipleBrokerClosuresHandledInOneCycle() {
    // Create 10 open positions
    List<PortfolioPosition> positions = createOpenPositions(10);
    
    // Close all in broker
    positions.forEach(p -> brokerStub.setPositionQuantity(p.getSymbol(), BigDecimal.ZERO));
    
    // Run reconciliation
    brokerReconciliationService.reconcilePositions(userId);
    
    // Verify: All closed, all suppressed, all audited
    for (PortfolioPosition p : positions) {
        PortfolioPosition updated = positionRepository.findById(p.getId()).get();
        assertThat(updated.getPositionState()).isEqualTo("CLOSED");
        
        ManualExitSuppression suppression = suppressionRepository.findByPositionId(p.getId()).get();
        assertThat(suppression.isSuppressionActive()).isTrue();
    }
}

@Test
@Transactional
void testBrokerClosureNotified() {
    // Setup position
    PortfolioPosition position = createOpenPosition("INFY", BigDecimal.ONE);
    
    // Close in broker
    brokerStub.setPositionQuantity("INFY", BigDecimal.ZERO);
    
    // Run reconciliation
    brokerReconciliationService.reconcilePositions(userId);
    
    // Verify trader terminal receives update
    TraderTerminalSnapshot terminal = traderTerminalService.getSnapshot(userId);
    assertThat(terminal.getOpenPositions()).doesNotContain("INFY");
}
```

### 2.4 Success Criteria
- ✅ Broker closure detected within one reconciliation cycle
- ✅ Synthetic exit created with execution link
- ✅ OMS position quantity = 0
- ✅ Manual exit suppression active
- ✅ Audit trail complete
- ✅ Portfolio updated
- ✅ Trader terminal updated
- ✅ No future exits attempted

---

## WORKSTREAM 3: CLOSED_BY_USER LIFECYCLE STATES

### 3.1 Requirement
Track position closure reason throughout lifecycle:
- CLOSED_BY_STRATEGY
- CLOSED_BY_USER
- CLOSED_BY_BROKER
- CLOSED_BY_RISK
- CLOSED_BY_KILLSWITCH

### 3.2 Files to Create/Modify

#### NEW: stokr-oms/src/main/java/com/stokr/oms/domain/PositionClosureReason.java
```java
public enum PositionClosureReason {
    OPEN("Position is currently open"),
    CLOSED_BY_STRATEGY("Closed by strategy signal (profit target or stop loss)"),
    CLOSED_BY_USER("Manually closed by user from broker or terminal"),
    CLOSED_BY_BROKER("Closed by broker (forced liquidation or corporate action)"),
    CLOSED_BY_RISK("Closed by risk engine (circuit breaker, max loss, etc)"),
    CLOSED_BY_KILLSWITCH("Closed by kill switch (system emergency stop)"),
    CLOSED_BY_MARKET_CLOSE("Closed by market close auto-exit system"),
    CLOSED_BY_RECONCILIATION("Closed during reconciliation (ghost position cleanup)");
    
    private final String description;
    
    PositionClosureReason(String description) {
        this.description = description;
    }
}
```

#### MODIFY: stokr-oms/src/main/java/com/stokr/oms/domain/PortfolioPosition.java
```java
public class PortfolioPosition {
    // ... existing fields ...
    
    @Column(name = "position_state")
    private String positionState = "OPEN";  // OPEN, CLOSING, CLOSED
    
    @Column(name = "closure_reason")
    @Enumerated(EnumType.STRING)
    private PositionClosureReason closureReason;
    
    @Column(name = "exit_source")
    private String exitSource;  // STRATEGY_SIGNAL, MANUAL_BROKER, etc.
    
    @Column(name = "position_closed_at")
    private Instant positionClosedAt;
    
    @Column(name = "manual_suppression_active")
    private Boolean manualSuppressionActive = false;
    
    // Methods
    public void closeByStrategy(UUID signalId, UUID executionId) {
        this.closureReason = PositionClosureReason.CLOSED_BY_STRATEGY;
        this.exitSource = "STRATEGY_SIGNAL";
        this.exitSignalId = signalId;
        this.exitExecutionId = executionId;
        this.positionState = "CLOSED";
        this.positionClosedAt = Instant.now();
    }
    
    public void closeByUser(String brokerExitSource, Instant exitTime) {
        this.closureReason = PositionClosureReason.CLOSED_BY_USER;
        this.exitSource = brokerExitSource;  // "ZERODHA_APP", "KITE_WEB", etc.
        this.positionState = "CLOSED";
        this.positionClosedAt = exitTime;
        this.manualSuppressionActive = true;
    }
    
    public void closeByBroker() {
        this.closureReason = PositionClosureReason.CLOSED_BY_BROKER;
        this.exitSource = "BROKER_LIQUIDATION";
        this.positionState = "CLOSED";
        this.positionClosedAt = Instant.now();
    }
    
    public void closeByRisk(String riskReason) {
        this.closureReason = PositionClosureReason.CLOSED_BY_RISK;
        this.exitSource = riskReason;  // "MAX_LOSS_HIT", "CIRCUIT_BREAKER", etc.
        this.positionState = "CLOSED";
        this.positionClosedAt = Instant.now();
    }
    
    public void closeByKillSwitch() {
        this.closureReason = PositionClosureReason.CLOSED_BY_KILLSWITCH;
        this.exitSource = "KILL_SWITCH";
        this.positionState = "CLOSED";
        this.positionClosedAt = Instant.now();
    }
    
    public boolean isManuallyClosed() {
        return closureReason == PositionClosureReason.CLOSED_BY_USER;
    }
    
    public boolean shouldSuppressExits() {
        return isManuallyClosed() && manualSuppressionActive;
    }
}
```

#### MODIFY: stokr-execution/src/main/java/com/stokr/execution/service/PressureSmartExitService.java
```java
/**
 * Before generating exit signal, check closure reason
 */
@Transactional
public void generateExitSignal(PortfolioPosition position) {
    // Check if already closed
    if (!position.getPositionState().equals("OPEN")) {
        log.warn("pressure_exit.skipped position_already_closed position={} state={}",
            position.getId(), position.getPositionState());
        return;
    }
    
    // Check if suppressed due to manual closure
    if (position.shouldSuppressExits()) {
        log.warn("pressure_exit.suppressed position_manually_closed position={} reason={}",
            position.getId(), position.getClosureReason());
        return;
    }
    
    // Proceed with exit...
}
```

### 3.3 Validation Tests
```java
@Test
void testPositionClosureReasonTracked() {
    PortfolioPosition position = createOpenPosition("INFY", BigDecimal.ONE);
    
    // Close by strategy
    position.closeByStrategy(signalId, executionId);
    positionRepository.save(position);
    
    PortfolioPosition updated = positionRepository.findById(position.getId()).get();
    assertThat(updated.getClosureReason()).isEqualTo(PositionClosureReason.CLOSED_BY_STRATEGY);
    assertThat(updated.getPositionState()).isEqualTo("CLOSED");
}

@Test
void testManualClosureSuppressesFutureExits() {
    PortfolioPosition position = createOpenPosition("INFY", BigDecimal.ONE);
    
    // User closes manually
    position.closeByUser("ZERODHA_APP", Instant.now());
    positionRepository.save(position);
    
    // Try to generate exit - should be suppressed
    pressureExitService.generateExitSignal(position);
    
    // Verify no exit signal was created
    List<StrategySignal> signals = signalRepository.findByPositionId(position.getId());
    assertThat(signals).isEmpty();
}
```

### 3.4 Success Criteria
- ✅ Every closed position has closure_reason
- ✅ Manual closures marked with CLOSED_BY_USER
- ✅ Manual closures suppress future exits
- ✅ Audit trail shows closure reason
- ✅ Dashboard displays closure reason

---

## WORKSTREAM 4: EXIT_ALL DURABILITY

### 4.1 Requirement
When user clicks EXIT_ALL (kill switch):
1. All positions exit
2. All strategies pause
3. Pause state persists across restarts
4. Pause state persists across deployments
5. No auto-resume

### 4.2 Files to Create/Modify

#### NEW: stokr-execution/src/main/java/com/stokr/execution/service/ExitAllService.java
```java
/**
 * Durable EXIT_ALL implementation.
 * 
 * Must survive:
 * - Application restart
 * - Deployment
 * - Reconciliation
 * - Market gaps
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ExitAllService {
    
    private final PortfolioPositionRepository positionRepository;
    private final StrategySignalRepository signalRepository;
    private final StrategyPauseStateRepository pauseStateRepository;
    private final OmsExecutionRepository executionRepository;
    private final PositionLifecycleAuditRepository auditRepository;
    private final PressureSmartExitService exitService;
    
    /**
     * User-initiated EXIT_ALL (durably pauses all strategies)
     */
    @Transactional
    public void exitAll(UUID userId) {
        Instant now = Instant.now();
        
        log.error("exit_all.initiated user={} timestamp={}", userId, now);
        
        // Step 1: Exit all open positions
        List<PortfolioPosition> openPositions = positionRepository.findOpenPositions(userId);
        int exitCount = 0;
        
        for (PortfolioPosition position : openPositions) {
            generateExitSignal(position, "EXIT_ALL initiated");
            exitCount++;
        }
        
        log.error("exit_all.exit_signals_generated user={} count={}", userId, exitCount);
        
        // Step 2: Pause ALL strategies (DURABLY)
        pauseAllStrategies(userId, "EXIT_ALL by user", now);
        
        // Step 3: Create audit trail
        auditRepository.save(PositionLifecycleAudit.builder()
            .userId(userId)
            .triggeredBy("USER")
            .sourceSystem("EXIT_ALL")
            .reason("User initiated EXIT_ALL")
            .eventTime(now)
            .build());
        
        log.error("exit_all.completed user={} positions_exited={}", userId, exitCount);
    }
    
    /**
     * Pause ALL strategies DURABLY
     * Must survive restart/deployment/reconciliation
     */
    @Transactional
    public void pauseAllStrategies(UUID userId, String reason, Instant pausedAt) {
        // Get all possible strategies
        List<String> strategies = getAvailableStrategies();
        
        for (String strategyName : strategies) {
            StrategyPauseState pauseState = StrategyPauseState.builder()
                .userId(userId)
                .strategyName(strategyName)
                
                .currentState("EXIT_ALL_PAUSED")
                .pauseReason(reason)
                
                .triggeredBy("USER")
                .triggeredById(userId)
                
                // CRITICAL: No resume condition
                .resumeCondition(null)  // Cannot auto-resume
                
                // CRITICAL: Survives everything
                .survivesRestart(true)
                .survivesDeployment(true)
                
                .pausedAt(pausedAt)
                .build();
            
            pauseStateRepository.saveOrUpdate(pauseState);
            
            log.error("exit_all.strategy_paused user={} strategy={} state=EXIT_ALL_PAUSED",
                userId, strategyName);
        }
    }
    
    /**
     * On application startup, restore EXIT_ALL pause state
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    @Transactional
    public void restoreExitAllPauseState() {
        // Find all users with EXIT_ALL_PAUSED state
        List<StrategyPauseState> pausedStates = pauseStateRepository
            .findByCurrentState("EXIT_ALL_PAUSED");
        
        for (StrategyPauseState state : pausedStates) {
            // Verify strategies are actually paused
            if (!isStrategyPaused(state.getUserId(), state.getStrategyName())) {
                // Re-pause (shouldn't happen, but safeguard)
                pauseStrategy(state.getUserId(), state.getStrategyName(), "EXIT_ALL_PAUSED");
            }
        }
        
        if (!pausedStates.isEmpty()) {
            log.error("exit_all.pause_state_restored count={}", pausedStates.size());
        }
    }
    
    private void generateExitSignal(PortfolioPosition position, String reason) {
        StrategySignal exitSignal = StrategySignal.builder()
            .userId(position.getUserId())
            .signalType(SignalType.EXIT)
            .symbol(position.getSymbol())
            .suggestedQty(position.getQuantity().abs())
            .reason(reason)
            .strategyName("EXIT_ALL")
            .pipeline("EMERGENCY")
            .testTrade(false)
            .simulation(false)
            .build();
        
        signalRepository.save(exitSignal);
    }
    
    private List<String> getAvailableStrategies() {
        // Return list of all active strategies
        return Arrays.asList(
            "INDEX_HUNT",
            "ADV_CASH",
            "GAP_FILL",
            "S3_VWAP_RETEST",
            "S7_RANGE_FADE",
            "EARLY_BREAKOUT",
            "PRE_OPEN_GAP_OI"
        );
    }
}
```

#### MODIFY: stokr-bootstrap/src/main/java/com/stokr/bootstrap/startup/StrategyRuntimeInitializer.java
```java
/**
 * On startup, check for EXIT_ALL paused strategies
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyRuntimeInitializer {
    
    private final StrategyPauseStateRepository pauseStateRepository;
    private final StrategyExecutionEngine executionEngine;
    
    @PostConstruct
    public void initializeStrategies() {
        log.info("strategy.startup_initialization_started");
        
        // Check for EXIT_ALL paused states
        List<StrategyPauseState> exitAllPaused = pauseStateRepository
            .findByCurrentState("EXIT_ALL_PAUSED");
        
        for (StrategyPauseState state : exitAllPaused) {
            log.error("strategy.exit_all_paused_restored user={} strategy={} reason={}",
                state.getUserId(), 
                state.getStrategyName(),
                state.getPauseReason());
            
            // Do NOT resume - ensure strategy stays paused
            executionEngine.pauseStrategy(state.getUserId(), state.getStrategyName(), "EXIT_ALL_PAUSED");
        }
        
        log.info("strategy.startup_initialization_complete exit_all_count={}", exitAllPaused.size());
    }
}
```

### 4.3 Validation Tests
```java
@Test
@Transactional
void testExitAllClosesAllPositions() {
    // Create 10 open positions
    List<PortfolioPosition> positions = createOpenPositions(10);
    
    // Exit all
    exitAllService.exitAll(userId);
    
    // Verify all have exit signals
    for (PortfolioPosition p : positions) {
        List<StrategySignal> exitSignals = signalRepository
            .findByPositionIdAndSignalType(p.getId(), SignalType.EXIT);
        assertThat(exitSignals).isNotEmpty();
    }
}

@Test
@Transactional
void testExitAllPausesAllStrategies() {
    exitAllService.exitAll(userId);
    
    List<StrategyPauseState> pausedStates = pauseStateRepository
        .findByUserIdAndCurrentState(userId, "EXIT_ALL_PAUSED");
    
    // Should pause all strategies
    assertThat(pausedStates.size()).isGreaterThan(5);
}

@Test
@Transactional
void testExitAllPauseStateSurvivesRestart() {
    // Set EXIT_ALL pause state
    exitAllService.pauseAllStrategies(userId, "Test", Instant.now());
    
    // Simulate restart (clear cache, reinitialize)
    applicationContext.restart();
    
    // Verify pause state still active
    List<StrategyPauseState> pausedStates = pauseStateRepository
        .findByUserIdAndCurrentState(userId, "EXIT_ALL_PAUSED");
    
    assertThat(pausedStates).isNotEmpty();
}

@Test
@Transactional
void testExitAllCannotAutoResume() {
    exitAllService.exitAll(userId);
    
    // Verify no resume_condition is set
    List<StrategyPauseState> pausedStates = pauseStateRepository
        .findByUserIdAndCurrentState(userId, "EXIT_ALL_PAUSED");
    
    for (StrategyPauseState state : pausedStates) {
        assertThat(state.getResumeCondition()).isNull();
    }
}
```

### 4.4 Success Criteria
- ✅ EXIT_ALL closes all open positions
- ✅ EXIT_ALL pauses all strategies
- ✅ Pause state persists across restart
- ✅ Pause state persists across deployment
- ✅ No auto-resume
- ✅ Strategies remain paused until explicit RESUME

---

# PART 3: DEPLOYMENT, VALIDATION, AND ROLLBACK

## 3.1 Deployment Phases

### Phase 1: Database Migrations (Immediate)
```sql
-- Run migrations in order:
1_create_position_lifecycle_audit.sql
2_create_strategy_pause_state.sql
3_create_manual_exit_suppression.sql
4_create_broker_reconciliation_event.sql
5_alter_portfolio_positions_add_ownership.sql
6_alter_oms_orders_add_signal_linkage.sql
7_alter_oms_executions_add_audit.sql
8_create_indices.sql
```

### Phase 2: Code Deployment (Rolling)
```
1. Deploy OmsExecutionSignalValidator
2. Deploy ExternalBrokerExitHandler
3. Deploy PositionClosureReason enum
4. Deploy ExitAllService
5. Deploy StrategyPauseState logic
6. Deploy reconciliation changes
7. Deploy trader terminal changes
8. Deploy strategy runtime changes
```

### Phase 3: Validation Gates (Automated)
```
Before each phase:
- Run integration tests
- Run production data validation
- Check for orphan records
- Verify no ghost positions
- Verify signal linkage
```

## 3.2 Validation Checklist

```
DATABASE:
☐ All new tables created
☐ All indices created
☐ All foreign keys valid
☐ No orphan records
☐ Position lifecycle audit has entries

SIGNALS & EXECUTIONS:
☐ 100% LIVE fills have signal_id
☐ 0 orphan executions
☐ All signals link to positions
☐ All executions link to signals

BROKER RECONCILIATION:
☐ Broker position closures detected
☐ Synthetic exits created
☐ OMS quantities updated
☐ Manual suppressions active
☐ Audit trail complete
☐ Broker = OMS = Portfolio = Terminal

POSITION OWNERSHIP:
☐ All closed positions have closure_reason
☐ Manual closures marked correctly
☐ Manual suppressions prevent future exits
☐ Audit trail complete

EXIT_ALL:
☐ All positions exit
☐ All strategies pause
☐ Pause state persists restart
☐ Pause state persists deployment
☐ Cannot auto-resume

TRADER TERMINAL:
☐ Shows broker quantity
☐ Shows OMS quantity
☐ Shows reconciliation status
☐ Updates on broker changes
☐ Displays manual suppressions
```

## 3.3 Rollback Procedure

```
If critical issue found:

1. IMMEDIATE:
   - Stop all strategies (manual toggle)
   - Cancel pending orders
   - Note error time

2. WITHIN 5 MIN:
   - Revert code to previous version
   - Restart application
   - Validate broker state

3. INSPECT:
   - Check position_lifecycle_audit
   - Check strategy_pause_state
   - Check manual_exit_suppression
   - Determine scope of issue

4. RECOVER:
   - Run reconciliation in safe mode
   - Fix any ghost positions
   - Verify trader terminal
   - Resume trading with approval

5. POST-MORTEM:
   - Document root cause
   - Plan corrective action
   - Update validation checklist
```

---

# PART 4: SUCCESS CRITERIA & VERIFICATION

## 4.1 Production Acceptance Test

```java
@SpringBootTest
public class ProductionAcceptanceTest {
    
    @Test
    @Transactional
    void testBrokerTruthPrincipleHoldsProdLike() {
        // Simulate complex trading day
        
        // 1. Multiple entry signals
        List<StrategySignal> entries = generateEntrySignals(20);
        List<PortfolioPosition> positions = executeSignals(entries);
        
        // 2. Manual broker exit (user exits from Zerodha)
        String symbol = positions.get(0).getSymbol();
        brokerSimulator.closePosition(symbol);
        
        // 3. Run reconciliation
        brokerReconciliationService.reconcilePositions(userId);
        
        // 4. Verify consistency
        assertBrokerTruth(userId);  // Broker = OMS = Portfolio = Terminal
        
        // 5. Verify no duplicate exits
        PortfolioPosition closedPos = positionRepository.findById(positions.get(0).getId()).get();
        assertThat(closedPos.shouldSuppressExits()).isTrue();
        
        // 6. More entries
        List<StrategySignal> moreEntries = generateEntrySignals(15);
        executeSignals(moreEntries);
        
        // 7. EXIT_ALL
        exitAllService.exitAll(userId);
        
        // 8. Verify all closed & paused
        assertAllPositionsClosed();
        assertAllStrategiesPaused();
        
        // 9. Restart application
        applicationContext.restart();
        
        // 10. Verify pause state persisted
        assertAllStrategiesStillPaused();
        
        // 11. Final reconciliation
        brokerReconciliationService.reconcilePositions(userId);
        
        // 12. Verify still consistent
        assertBrokerTruth(userId);
    }
    
    private void assertBrokerTruth(UUID userId) {
        // Broker positions
        Map<String, BigDecimal> brokerQtys = getBrokerQuantities(userId);
        
        // OMS positions
        Map<String, BigDecimal> omsQtys = getOmsQuantities(userId);
        
        // Portfolio positions
        Map<String, BigDecimal> portfolioQtys = getPortfolioQuantities(userId);
        
        // Terminal positions
        Map<String, BigDecimal> terminalQtys = getTerminalQuantities(userId);
        
        // All must match
        assertThat(brokerQtys).isEqualTo(omsQtys);
        assertThat(omsQtys).isEqualTo(portfolioQtys);
        assertThat(portfolioQtys).isEqualTo(terminalQtys);
    }
}
```

## 4.2 Metrics to Track

```
Daily Monitoring:
- Orphan executions (target: 0)
- Ghost positions (target: 0)
- Duplicate order rejections (target: <5% of orders)
- Reconciliation discrepancies resolved (target: <1 minute)
- Manual exit suppressions active (target: 100%)
- Signal linkage failures (target: 0)
- Broker = OMS mismatches (target: 0 after reconciliation)

Weekly Reports:
- Reconciliation cycle health
- Position closure audit trail completeness
- Strategy pause state durability
- Exit_all event frequency & impact
```

---

# PART 5: DETAILED STEP-BY-STEP IMPLEMENTATION ROADMAP

## Week 1: Database & Domain Models

### Day 1: Schema Migrations
```
TASK 1.1: Create position_lifecycle_audit table
- File: migration_001_position_lifecycle_audit.sql
- Review: ✓ FK constraints ✓ Indices ✓ Partitioning
- Test: ✓ Insert 1000 records ✓ Query performance

TASK 1.2: Create strategy_pause_state table  
- File: migration_002_strategy_pause_state.sql
- Review: ✓ Persistence requirements ✓ Constraints
- Test: ✓ Restart persistence ✓ Concurrent updates

TASK 1.3: Create manual_exit_suppression table
- File: migration_003_manual_exit_suppression.sql
- Test: ✓ Unique constraint ✓ FK validation

TASK 1.4: Create broker_reconciliation_event table
- File: migration_004_broker_reconciliation_event.sql
- Test: ✓ Event tracking ✓ Resolution workflow
```

### Day 2-3: Domain Model Changes
```
TASK 2.1: Add columns to portfolio_positions
- File: migration_005_portfolio_positions_ownership.sql
- Columns: position_state, owner_type, exit_source, etc.
- Test: ✓ Backward compatibility ✓ Default values

TASK 2.2: Add columns to oms_orders
- File: migration_006_oms_orders_signal_linkage.sql
- Columns: signal_id, broker_order_id, is_duplicate
- Test: ✓ Unique constraints ✓ FK validation

TASK 2.3: Add columns to oms_executions
- File: migration_007_oms_executions_audit.sql
- Columns: signal_id, is_synthetic, synthetic_reason
- Test: ✓ Synthetic flag validation
```

### Day 4-5: Entity Classes
```
TASK 3.1: Create PositionClosureReason enum
- File: stokr-oms/domain/PositionClosureReason.java

TASK 3.2: Update PortfolioPosition entity
- Add closure reason methods
- Add suppression checks
- Add state machine helpers

TASK 3.3: Create StrategyPauseState entity
- File: stokr-execution/domain/StrategyPauseState.java

TASK 3.4: Create ManualExitSuppression entity
- File: stokr-oms/domain/ManualExitSuppression.java

TASK 3.5: Create PositionLifecycleAudit entity
- File: stokr-oms/domain/PositionLifecycleAudit.java
```

---

## Week 2: Core Services (Workstreams 1-4)

### Day 1: Signal Linkage (WS1)
```
TASK 4.1: Create OmsExecutionSignalValidator
- File: stokr-oms/execution/OmsExecutionSignalValidator.java
- Methods: validateExecutionSignalLinkage(), logOrphanExecution()
- Test: ✓ Reject orphans ✓ Accept linked ✓ Audit logs

TASK 4.2: Integrate validator into OrderLifecycleService
- Modify: stokr-oms/service/OrderLifecycleService.java
- Call validator before persistence
- Test: ✓ Unit tests ✓ Integration tests
```

### Day 2: Broker Exit Handling (WS2)
```
TASK 5.1: Create ExternalBrokerExitHandler
- File: stokr-execution/reconciliation/ExternalBrokerExitHandler.java
- Methods: handleBrokerPositionClosure()
- Creates: Synthetic exit, suppression, audit trail
- Test: ✓ Single position ✓ Multiple positions ✓ Edge cases

TASK 5.2: Integrate into BrokerReconciliationService
- Modify: stokr-oms/reconciliation/BrokerReconciliationService.java
- Call handler when broker qty=0, oms qty!=0
- Test: ✓ Detection ✓ Resolution ✓ Audit trail
```

### Day 3: Position Closure Tracking (WS3)
```
TASK 6.1: Update PressureSmartExitService
- Modify: stokr-execution/service/PressureSmartExitService.java
- Check position closure reason before exit
- Check manual suppression flag
- Test: ✓ Skip already closed ✓ Skip suppressed

TASK 6.2: Update MarketCloseExitSignalGenerator
- Modify: stokr-execution/service/MarketCloseExitSignalGenerator.java
- Check closure reason for each position
- Test: ✓ Skip manually closed ✓ Audit trail
```

### Day 4-5: EXIT_ALL Durability (WS4)
```
TASK 7.1: Create ExitAllService
- File: stokr-execution/service/ExitAllService.java
- Methods: exitAll(), pauseAllStrategies()
- Persistence: survives restart, deployment
- Test: ✓ Restart persistence ✓ Deployment persistence

TASK 7.2: Create StrategyRuntimeInitializer
- File: stokr-bootstrap/startup/StrategyRuntimeInitializer.java
- On startup: restore EXIT_ALL paused states
- Test: ✓ Pause state restored ✓ Strategies stay paused

TASK 7.3: Update strategy runtime
- Modify: Strategy execution engines
- Check pause state before running
- Test: ✓ Respect pause state ✓ No auto-resume
```

---

## Week 3: Integration & Validation

### Day 1: Integration Points
```
TASK 8.1: TraderTerminalService integration
- Modify: stokr-bootstrap/terminal/TraderTerminalService.java
- Receive broker update events
- Display reconciliation status
- Test: ✓ Real-time updates ✓ Consistency checks

TASK 8.2: WebSocket event handling
- Modify: Zerodha position change handler
- Trigger reconciliation on broker change
- Test: ✓ Event propagation ✓ Handling

TASK 8.3: Risk engine integration
- Modify: RiskEngine
- Trigger EXIT_ALL on circuit breaker
- Test: ✓ Risk scenarios ✓ Durability
```

### Day 2-3: Comprehensive Testing
```
TASK 9.1: Unit tests for all services
- OmsExecutionSignalValidator
- ExternalBrokerExitHandler
- ExitAllService
- StrategyPauseState logic
- Test coverage: >95%

TASK 9.2: Integration tests
- Signal linkage flow
- Broker closure handling
- Manual suppression
- EXIT_ALL durability
- Trader terminal consistency

TASK 9.3: Production simulation tests
- ProductionAcceptanceTest.java
- 100+ simulated trading days
- Random events (manual exits, broker changes)
- Verify Broker = OMS always
```

### Day 4-5: Load Testing & Performance
```
TASK 10.1: Reconciliation performance
- 1000+ positions
- 10+ reconciliation cycles
- Verify sub-second linkage resolution
- Monitor database performance

TASK 10.2: Event throughput
- 100 position changes/second
- Verify no lag in updates
- Trader terminal responsiveness
```

---

## Week 4: Deployment & Production Validation

### Day 1-2: Staged Deployment
```
Phase 1: DEV Environment
- Deploy all code
- Run full test suite
- Manual validation

Phase 2: UAT Environment  
- Deploy with real Zerodha connectivity
- Test with live market data
- Manual broker exit tests
- Validation sign-off

Phase 3: PROD (Low-Traffic Window)
- Deploy database migrations
- Deploy code
- Continuous monitoring
- Rollback plan ready
```

### Day 3-4: Continuous Validation
```
TASK 11.1: Daily metrics
- Orphan executions: 0
- Ghost positions: 0
- Signal linkage: 100%
- Reconciliation success: 100%

TASK 11.2: Alert thresholds
- Duplicate rejections > 10% → Alert
- Reconciliation > 2 min → Alert
- Broker mismatch > 1 min → Alert
- EXIT_ALL triggered → Log & notify

TASK 11.3: Daily audit review
- position_lifecycle_audit completeness
- strategy_pause_state correctness
- manual_exit_suppression active records
- Broker reconciliation success rate
```

### Day 5: Production Acceptance
```
TASK 12.1: Sign-off checklist
- Database migrations complete ✓
- All tests passing ✓
- Zero orphan/ghost records ✓
- Broker truth verified ✓
- Manual exit handling verified ✓
- EXIT_ALL durability verified ✓
- Trader terminal in sync ✓

TASK 12.2: Runbook updates
- Deployment procedures
- Rollback procedures
- On-call escalation
- Emergency procedures

TASK 12.3: Team training
- Code review
- Architecture walkthrough
- Operational procedures
```

---

# DELIVERABLES CHECKLIST

## Code Deliverables
```
WS1 - Signal Linkage:
☐ OmsExecutionSignalValidator.java
☐ OrderLifecycleService.java (modified)

WS2 - Broker Exit Handling:
☐ ExternalBrokerExitHandler.java
☐ BrokerReconciliationService.java (modified)

WS3 - Closure Tracking:
☐ PositionClosureReason.java (enum)
☐ PortfolioPosition.java (modified)
☐ PressureSmartExitService.java (modified)

WS4 - EXIT_ALL Durability:
☐ ExitAllService.java
☐ StrategyRuntimeInitializer.java
☐ Strategy execution engines (modified)

Database:
☐ 8 migration files (.sql)
☐ All indices created
☐ All foreign keys configured

Tests:
☐ 50+ unit tests
☐ 20+ integration tests
☐ ProductionAcceptanceTest
☐ Load test suite
```

## Documentation Deliverables
```
☐ Architecture document
☐ Database schema document
☐ API contract changes
☐ State machine diagrams
☐ Integration point documentation
☐ Deployment runbook
☐ Rollback procedures
☐ Operations manual
☐ Troubleshooting guide
```

## Validation Deliverables
```
☐ Test result report
☐ Performance benchmark
☐ Production validation report
☐ Metrics dashboard
☐ Alert configuration
☐ Runbook
```

---

# ENHANCED PROMPT IS COMPLETE

This detailed specification covers:
✅ Database schema changes
✅ Domain model updates
✅ 10 detailed workstreams
✅ Step-by-step implementation roadmap
✅ Comprehensive validation strategy
✅ Deployment plan with rollback
✅ Success criteria & metrics
✅ All deliverables specified

Ready for implementation.

