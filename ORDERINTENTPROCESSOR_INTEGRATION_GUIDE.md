# OrderIntentProcessor Integration Guide

## Overview

You need to make **3 simple modifications** to OrderIntentProcessor.java:

1. Add 1 dependency injection
2. Add 1 try-catch wrapper
3. Add 8 signal tracking calls

**Total time: 15 minutes**

---

## Step 1: Add Dependency Injection

### **Find This:**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIntentProcessor {
    
    private final SomeService someService;
    private final AnotherService anotherService;
    // ... other fields
}
```

### **Change To:**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIntentProcessor {
    
    private final SomeService someService;
    private final AnotherService anotherService;
    // ... other fields
    
    // 👈 ADD THESE TWO (CRITICAL!)
    private final SignalExecutionTrackingService trackingService;
    private final TransactionRollbackGuardService rollbackGuard;
}
```

---

## Step 2: Add Rollback Guard at Method Start

### **Find This:**
```java
@Transactional
public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
    StrategySignalEntity signal = signalRepository.findById(msg.signalId())
        .orElseThrow(() -> new Exception("Signal not found"));
    UUID userId = resolveUserId(msg, signal);
    
    // ... rest of method
}
```

### **Change To:**
```java
@Transactional
public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
    StrategySignalEntity signal = signalRepository.findById(msg.signalId())
        .orElseThrow(() -> new Exception("Signal not found"));
    UUID userId = resolveUserId(msg, signal);
    
    // 👈 ADD THIS (CRITICAL! Must be FIRST)
    rollbackGuard.registerRollbackGuard(signal.getId(), userId);
    
    try {
        // ... rest of method (EVERYTHING from here...)
        
    } catch (Exception ex) {
        // 👈 ADD THIS at the very end (CRITICAL!)
        log.error("Signal execution failed", ex);
        rollbackGuard.handleRollback(signal.getId(), userId, ex);
        throw ex;
    }
}
```

---

## Step 3: Add Signal Tracking Calls (8 Gates)

Now find these 8 locations in your processSignalIntent method and add tracking calls.

### **Gate 1: Strategy Validation**

**Find this code:**
```java
Optional<StrategyDefinition> defOpt = strategyRepository.findByStrategyKey(sigStrategyKey);
if (defOpt.isEmpty()) {
    log.error("Strategy not found: {}", sigStrategyKey);
    return;  // ← Failure point
}
```

**Add tracking:**
```java
Optional<StrategyDefinition> defOpt = strategyRepository.findByStrategyKey(sigStrategyKey);
if (defOpt.isEmpty()) {
    log.error("Strategy not found: {}", sigStrategyKey);
    // 👈 ADD THIS
    trackingService.recordFailure(signal.getId(), userId,
        SignalExecutionTrack.SignalExecutionStatus.VALIDATION_FAILED,
        "STRATEGY_NOT_FOUND",
        "Strategy key: " + sigStrategyKey);
    return;
}
```

---

### **Gate 2: Execution Mode Resolution**

**Find this code:**
```java
ExecutionMode mode = determineExecutionMode(signal, signal.getExecutionMode());
log.info("Execution mode resolved: {}", mode);
```

**Add tracking:**
```java
ExecutionMode mode = determineExecutionMode(signal, signal.getExecutionMode());
log.info("Execution mode resolved: {}", mode);

// 👈 ADD THIS
trackingService.recordStep(signal.getId(), userId,
    "Execution mode resolved: " + mode.name(),
    SignalExecutionTrack.SignalExecutionStatus.MODE_RESOLVED);
```

---

### **Gate 3: Position Sizing**

**Find this code:**
```java
PositionSizing sizing = calculatePositionSizing(signal, reconciliationStatus);
log.info("Position sizing: {}", sizing.normalizedQuantity());
```

**Add tracking:**
```java
PositionSizing sizing = calculatePositionSizing(signal, reconciliationStatus);
log.info("Position sizing: {}", sizing.normalizedQuantity());

// 👈 ADD THIS
trackingService.recordStep(signal.getId(), userId,
    "Position sizing calculated: " + sizing.normalizedQuantity() + " shares",
    SignalExecutionTrack.SignalExecutionStatus.SIZING_OK);
```

---

### **Gate 4: Risk Check Approval**

**Find this code:**
```java
boolean riskApproved = riskCheckService.approveOrder(signal, sizing);
if (!riskApproved) {
    log.error("Risk check failed");
    return;  // ← Failure point
}
```

**Add tracking:**
```java
boolean riskApproved = riskCheckService.approveOrder(signal, sizing);
if (!riskApproved) {
    log.error("Risk check failed");
    // 👈 ADD THIS
    trackingService.recordFailure(signal.getId(), userId,
        SignalExecutionTrack.SignalExecutionStatus.RISK_FAILED,
        "RISK_CHECK_FAILED",
        "Risk parameters exceeded");
    return;
}

// 👈 ADD THIS for success
trackingService.recordStep(signal.getId(), userId,
    "Risk check passed",
    SignalExecutionTrack.SignalExecutionStatus.RISK_CHECK);
```

---

### **Gate 5: Order Creation**

**Find this code:**
```java
Order order = createOrder(signal, sizing, mode);
log.info("Order created: {}", order.getId());
```

**Add tracking:**
```java
Order order = createOrder(signal, sizing, mode);
log.info("Order created: {}", order.getId());

// 👈 ADD THIS
trackingService.recordOrderCreated(signal.getId(), userId,
    order.getId(),
    mode.name(),
    order.getSide().name(),
    order.getQuantity());
```

---

### **Gate 6: Broker Submission**

**Find this code:**
```java
BrokerResponse brokerResponse = brokerService.submitOrder(order);
String brokerOrderId = brokerResponse.getOrderId();
log.info("Submitted to broker: {}", brokerOrderId);
```

**Add tracking:**
```java
BrokerResponse brokerResponse = brokerService.submitOrder(order);
String brokerOrderId = brokerResponse.getOrderId();
log.info("Submitted to broker: {}", brokerOrderId);

// 👈 ADD THIS
trackingService.recordBrokerSubmission(signal.getId(), userId,
    brokerOrderId,
    order.getBrokerVendor().name());
```

---

### **Gate 7: Broker Acceptance**

**Find this code:**
```java
if (brokerResponse.getStatus().equals("ACCEPTED")) {
    log.info("Broker accepted order");
    BigDecimal entryPrice = brokerResponse.getPrice();
}
```

**Add tracking:**
```java
if (brokerResponse.getStatus().equals("ACCEPTED")) {
    log.info("Broker accepted order");
    BigDecimal entryPrice = brokerResponse.getPrice();
    
    // 👈 ADD THIS
    trackingService.recordBrokerAccepted(signal.getId(), userId,
        brokerOrderId,
        entryPrice);
}
```

---

### **Gate 8: Order Filled**

**Find this code:**
```java
if (fillNotification.isFilled()) {
    log.info("Order filled completely");
    // Update OMS positions
}
```

**Add tracking:**
```java
if (fillNotification.isFilled()) {
    log.info("Order filled completely");
    
    // 👈 ADD THIS
    trackingService.recordFilled(signal.getId(), userId);
    
    // Update OMS positions
}
```

---

## Complete Integration Example

Here's what the final method should look like (simplified):

```java
@Transactional
public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
    StrategySignalEntity signal = signalRepository.findById(msg.signalId()).orElseThrow();
    UUID userId = resolveUserId(msg, signal);
    
    // 👈 CRITICAL: Register rollback guard FIRST
    rollbackGuard.registerRollbackGuard(signal.getId(), userId);
    
    try {
        // Initialize tracking
        trackingService.initializeTrack(signal, userId);
        
        // Gate 1: Strategy validation
        Optional<StrategyDefinition> defOpt = strategyRepository.findByStrategyKey(sigStrategyKey);
        if (defOpt.isEmpty()) {
            trackingService.recordFailure(signal.getId(), userId,
                SignalExecutionTrack.SignalExecutionStatus.VALIDATION_FAILED,
                "STRATEGY_NOT_FOUND", sigStrategyKey);
            return;
        }
        
        // Gate 2: Execution mode
        ExecutionMode mode = determineExecutionMode(signal, signal.getExecutionMode());
        trackingService.recordStep(signal.getId(), userId,
            "Execution mode: " + mode.name(),
            SignalExecutionTrack.SignalExecutionStatus.MODE_RESOLVED);
        
        // Gate 3: Position sizing
        PositionSizing sizing = calculatePositionSizing(signal);
        trackingService.recordStep(signal.getId(), userId,
            "Position sizing: " + sizing.normalizedQuantity() + " shares",
            SignalExecutionTrack.SignalExecutionStatus.SIZING_OK);
        
        // Gate 4: Risk check
        boolean riskApproved = riskCheckService.approveOrder(signal, sizing);
        if (!riskApproved) {
            trackingService.recordFailure(signal.getId(), userId,
                SignalExecutionTrack.SignalExecutionStatus.RISK_FAILED,
                "RISK_CHECK_FAILED", "Risk parameters exceeded");
            return;
        }
        trackingService.recordStep(signal.getId(), userId,
            "Risk check passed",
            SignalExecutionTrack.SignalExecutionStatus.RISK_CHECK);
        
        // Gate 5: Order creation
        Order order = createOrder(signal, sizing, mode);
        trackingService.recordOrderCreated(signal.getId(), userId,
            order.getId(), mode.name(), order.getSide().name(), order.getQuantity());
        
        // Gate 6: Broker submission
        BrokerResponse brokerResponse = brokerService.submitOrder(order);
        String brokerOrderId = brokerResponse.getOrderId();
        trackingService.recordBrokerSubmission(signal.getId(), userId,
            brokerOrderId, order.getBrokerVendor().name());
        
        // Gate 7: Broker acceptance
        if (brokerResponse.getStatus().equals("ACCEPTED")) {
            trackingService.recordBrokerAccepted(signal.getId(), userId,
                brokerOrderId, brokerResponse.getPrice());
        }
        
        // Gate 8: Fill notification
        FillNotification fill = awaitFillNotification(brokerOrderId);
        if (fill.isFilled()) {
            trackingService.recordFilled(signal.getId(), userId);
            updateOmsPositions(order, fill);
        }
        
        // ✅ Success!
        log.info("Signal execution completed: {}", signal.getId());
        
    } catch (Exception ex) {
        // 👈 CRITICAL: Catch-all with rollback protection
        log.error("Signal execution failed", ex);
        rollbackGuard.handleRollback(signal.getId(), userId, ex);
        throw ex;
    }
}
```

---

## Verification Checklist

After making these changes, verify:

- [ ] Code compiles: `mvn clean compile`
- [ ] Tests pass: `mvn test`
- [ ] No new warnings/errors
- [ ] All 8 tracking calls added
- [ ] Try-catch wrapper in place
- [ ] Rollback guard registration at start
- [ ] Rollback guard exception handler at end

---

## If You Get Stuck

**Issue**: "Symbol cannot be resolved"  
**Solution**: Make sure both services are injected:
```java
private final SignalExecutionTrackingService trackingService;
private final TransactionRollbackGuardService rollbackGuard;
```

**Issue**: "Transactional method raises checked exception"  
**Solution**: Wrap checked exceptions:
```java
catch (Exception ex) {
    rollbackGuard.handleRollback(signal.getId(), userId, ex);
    throw new RuntimeException(ex);
}
```

**Issue**: "UUID field not found"  
**Solution**: Use the actual field names in your entity:
```java
signal.getId()          // Or signal.getSignalId() depending on your entity
userId                  // Already resolved before try block
```

---

## Testing After Integration

### **1. Compile Check**
```bash
mvn clean compile
```

### **2. Unit Tests**
```bash
mvn test
```

### **3. Start Server**
```bash
mvn spring-boot:run
```

### **4. Generate Test Signal**
Use your UI or API to create a signal

### **5. Verify Tracking**
```bash
curl http://localhost:8080/api/trader/signals/dashboard \
  -H "X-User-Id: YOUR_USER_ID"
```

### **6. Check Database**
```sql
SELECT * FROM signal_execution_tracks 
ORDER BY created_at DESC LIMIT 1;
```

Should show your test signal with status = GENERATED

---

## Total Time Estimate

| Task | Time |
|------|------|
| Read this guide | 5 min |
| Add 2 injections | 2 min |
| Add try-catch wrapper | 3 min |
| Add 8 tracking calls | 5 min |
| Compile & verify | 5 min |
| **TOTAL** | **20 min** |

---

## That's It!

Once you complete these 3 modifications, OrderIntentProcessor will:
- ✅ Detect transaction rollbacks
- ✅ Track every signal through execution
- ✅ Record all failures
- ✅ Enable auto-retry for failed signals
- ✅ Provide real-time dashboard visibility

**Ready to integrate?** Print this file and follow the 8 gates. You've got this! 💪

