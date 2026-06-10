# SIGNAL PIPELINE OBSERVABILITY PLAN
## Complete Instrumentation Design for Timestamp Capture

Date: 2026-06-09  
Scope: Complete signal pipeline from market acceleration to broker execution  
Methodology: Code-level instrumentation design (NO IMPLEMENTATION)  

---

## PIPELINE FLOW OVERVIEW

```
Market Data Ingestion (Candle data)
    ↓ [STAGE 1]
Market Acceleration Detection (CatalogDrivenScanScheduler.scan())
    ↓ [STAGE 2]
Strategy Evaluation (StrategyRegistry.evaluate())
    ↓ [STAGE 3]
Signal Detection (StrategySignal condition met)
    ↓ [STAGE 4]
Confidence Calculation (ConfidenceEngineV2.calculateScore())
    ↓ [STAGE 5]
Risk Evaluation (RiskEngineService.evaluate())
    ↓ [STAGE 6]
Signal Persistence (StrategySignalPipelineService.persistAndDispatch())
    ↓ [STAGE 7]
Order Intent Creation (OmsIntentDispatcher.dispatch())
    ↓ [STAGE 8]
OMS Order Creation (OmsOrderService.createOrder())
    ↓ [STAGE 9]
Broker Submission (BrokerIntegration.submitOrder())
    ↓ [STAGE 10]
Broker Execution Confirmation
```

---

## STAGE 1: MARKET ACCELERATION DETECTION

### Current State
**Class:** `CatalogDrivenScanScheduler`  
**Method:** `scan()`  
**Location:** `./stokr-strategy/src/main/java/com/stokr/strategy/catalog/CatalogDrivenScanScheduler.java`

**Current Logging:**
```java
@Scheduled(fixedDelayString = "${stokr.catalog.scan.poll-ms:60000}")
public void scan() {
    Instant tick = Instant.now();
    // ... scanning logic
}
```

**What Exists:**
- ✅ Scheduler runs every 60 seconds (configurable)
- ✅ `Instant tick = Instant.now()` captures scheduler invocation time
- ✅ Tick is used for various checks

**What's Missing:**
- ❌ Tick timestamp is NOT persisted to database
- ❌ No audit trail of which candles triggered acceleration detection
- ❌ No log of condition evaluation timestamps

### Instrumentation Design

**Field to Add to `StrategySignalEntity`:**
```
Column: scheduler_tick_time (timestamp with timezone)
Purpose: When the CatalogDrivenScanScheduler tick occurred that detected this signal
Type: Instant (JPA)
```

**Implementation Point:**
```
File: CatalogDrivenScanScheduler.java
Method: scan()
After: Instant tick = Instant.now();
Action: Capture and pass `tick` to all downstream methods
```

**Latency Metric:**
```
acceleration_detection_latency = scheduler_tick_time - candle_close_time
Expected: 0-15 seconds (within same scheduler cycle)
```

---

## STAGE 2: STRATEGY EVALUATION

### Current State
**Class:** `StrategyRegistry`  
**Method:** `evaluate(String strategyKey, EvaluationContext context)`  
**Location:** `./stokr-strategy/src/main/java/com/stokr/strategy/registry/StrategyRegistry.java`

**What Exists:**
- ✅ Registry evaluates strategy conditions
- ✅ Returns StrategySignal objects
- ✅ No timestamp capture

**What's Missing:**
- ❌ Evaluation timestamp
- ❌ Condition met detection time
- ❌ Metric calculation completion time

### Instrumentation Design

**Field to Add to `StrategySignalEntity`:**
```
Column: evaluation_complete_time (timestamp with timezone)
Purpose: When strategy evaluation completed and signal was first created
Type: Instant (JPA)
```

**Implementation Point:**
```
File: StrategyRegistry.java
Method: evaluate()
Action: Capture Instant at method RETURN (signal created)
        signalBuilder.evaluationCompleteTime(Instant.now())
```

**Latency Metric:**
```
evaluation_latency = evaluation_complete_time - scheduler_tick_time
Expected: 50-200ms per strategy evaluation
```

---

## STAGE 3: SIGNAL DETECTION

### Current State
**Class:** `StrategyRegistry`  
**Method:** `evaluate()`  
**Location:** Same as Stage 2

**What Exists:**
- ✅ Signal is created when conditions are met
- ✅ StrategySignal object instantiated
- ✅ No detection timestamp

**What's Missing:**
- ❌ Exact moment conditions became true
- ❌ Which condition triggered first
- ❌ Detection timestamp separate from creation

### Instrumentation Design

**Field to Add to `StrategySignalEntity`:**
```
Column: condition_detection_time (timestamp with timezone)
Purpose: When signal conditions were FIRST detected as met
Type: Instant (JPA)
```

**Implementation Point:**
```
File: StrategyRegistry.java (or individual strategy classes)
Method: In condition evaluation logic (before signal creation)
Action: When ALL conditions become true:
        signalBuilder.conditionDetectionTime(Instant.now())
```

**Latency Metric:**
```
detection_to_creation_latency = evaluation_complete_time - condition_detection_time
Expected: <100ms (typically immediate)
```

---

## STAGE 4: CONFIDENCE CALCULATION

### Current State
**Class:** `ConfidenceEngineV2`  
**Method:** `calculateScore(ConfidenceContext context)`  
**Location:** `./stokr-strategy/src/main/java/com/stokr/strategy/metrics/ConfidenceEngineV2.java`

**What Exists:**
- ✅ Calculates all 8 components
- ✅ Aggregates to final confidence score
- ✅ No timing information

**What's Missing:**
- ❌ Component calculation start time
- ❌ Component completion times
- ❌ Total confidence calculation time

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: confidence_calculation_start_time (timestamp with timezone)
Purpose: When confidence component calculation began
Type: Instant

Column: confidence_calculation_end_time (timestamp with timezone)
Purpose: When confidence score was finalized
Type: Instant
```

**Implementation Point:**
```
File: ConfidenceEngineV2.java
Method: calculateScore()
Entry: confidenceStartTime = Instant.now()
Exit:  confidenceEndTime = Instant.now()
       signalBuilder.confidenceCalcStartTime(confidenceStartTime)
       signalBuilder.confidenceCalcEndTime(confidenceEndTime)
```

**Latency Metric:**
```
confidence_calculation_latency = confidence_calculation_end_time - confidence_calculation_start_time
Expected: 20-50ms (8 component calculations)
```

---

## STAGE 5: RISK EVALUATION

### Current State
**Class:** `RiskEngineService`  
**Method:** `evaluate(RiskContext context)`  
**Location:** `./stokr-risk/src/main/java/com/stokr/risk/service/RiskEngineService.java`

**What Exists:**
- ✅ Evaluates all risk rules
- ✅ Returns RiskDecision (APPROVED/REJECTED)
- ✅ Has RiskEvaluationTraceService for audit

**What's Missing:**
- ❌ Risk evaluation start timestamp
- ❌ Risk evaluation end timestamp
- ❌ Queue wait time (time before evaluation starts)

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: risk_queue_entry_time (timestamp with timezone)
Purpose: When signal entered risk engine queue (before evaluation)
Type: Instant

Column: risk_evaluation_start_time (timestamp with timezone)
Purpose: When risk evaluation actually began
Type: Instant

Column: risk_evaluation_end_time (timestamp with timezone)
Purpose: When risk evaluation completed
Type: Instant
```

**Implementation Point:**
```
File: RiskEngineService.java
Method: evaluate()
Entry: riskStartTime = Instant.now()
       signalBuilder.riskEvaluationStartTime(riskStartTime)

Exit:  riskEndTime = Instant.now()
       signalBuilder.riskEvaluationEndTime(riskEndTime)

File: Signal dispatch point (before risk engine)
Action: signalBuilder.riskQueueEntryTime(Instant.now())
```

**Latency Metrics:**
```
risk_queue_wait_latency = risk_evaluation_start_time - risk_queue_entry_time
Expected: 0-60 seconds (queue depth dependent)

risk_evaluation_latency = risk_evaluation_end_time - risk_evaluation_start_time
Expected: 10-30ms per rule evaluation
```

---

## STAGE 6: SIGNAL PERSISTENCE

### Current State
**Class:** `StrategySignalPipelineService`  
**Method:** `persistAndDispatch(StrategySignalEntity signal, ...)`  
**Location:** `./stokr-strategy/src/main/java/com/stokr/strategy/pipeline/StrategySignalPipelineService.java`

**What Exists:**
- ✅ `created_at` is set by JPA @CreationTimestamp
- ✅ Signal is saved to database
- ✅ No intermediate timing

**What's Missing:**
- ❌ Pre-persistence latency capture
- ❌ Persistence completion confirmation
- ❌ Database write latency

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: persistence_request_time (timestamp with timezone)
Purpose: When persistence request was submitted
Type: Instant

Column: persistence_complete_time (timestamp with timezone)
Purpose: When signal was successfully persisted to database
Type: Instant (same as created_at in practice)

Column: cumulative_latency_to_persistence (long milliseconds)
Purpose: Sum of all latencies from acceleration peak to persistence
Type: Long
```

**Implementation Point:**
```
File: StrategySignalPipelineService.java
Method: persistAndDispatch()
Entry: signalEntity.setPersistenceRequestTime(Instant.now())

After: repository.save(signalEntity)
       signalEntity.setPersistenceCompleteTime(Instant.now())

Calculation:
       cumulativeLatency = Duration.between(
           marketAccelerationPeakTime,
           persistenceCompleteTime
       ).toMillis()
       signalEntity.setCumulativeLatencyToPersistence(cumulativeLatency)
```

**Latency Metrics:**
```
persistence_latency = persistence_complete_time - persistence_request_time
Expected: 10-50ms (database write time)

cumulative_to_persistence = persistence_complete_time - market_acceleration_peak
Expected: 50-270 seconds (current observed range)
```

---

## STAGE 7: ORDER INTENT CREATION

### Current State
**Class:** `OmsIntentDispatcher`  
**Method:** `dispatch(SignalDispatchContext context)`  
**Location:** `./stokr-common/src/main/java/com/stokr/common/pipeline/OmsIntentDispatcher.java`

**What Exists:**
- ✅ Interface for intent dispatch
- ✅ Implementation in DefaultOmsIntentDispatcher
- ✅ No timestamp capture

**What's Missing:**
- ❌ Intent creation timestamp
- ❌ Intent dispatch completion timestamp
- ❌ Messaging queue latency

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: order_intent_creation_time (timestamp with timezone)
Purpose: When order intent was created
Type: Instant

Column: order_intent_dispatch_time (timestamp with timezone)
Purpose: When order intent was dispatched to messaging system
Type: Instant
```

**Implementation Point:**
```
File: DefaultOmsIntentDispatcher.java
Method: dispatch()
Entry: intentCreationTime = Instant.now()
       signalUpdateService.updateIntentCreationTime(
           signalId, 
           intentCreationTime
       )

Exit:  intentDispatchTime = Instant.now()
       signalUpdateService.updateIntentDispatchTime(
           signalId,
           intentDispatchTime
       )
```

**Latency Metrics:**
```
intent_creation_latency = order_intent_dispatch_time - order_intent_creation_time
Expected: 5-20ms (object creation + queue push)

cumulative_to_intent = order_intent_dispatch_time - market_acceleration_peak
Expected: 50-280 seconds
```

---

## STAGE 8: OMS ORDER CREATION

### Current State
**Class:** `OmsOrderService`  
**Method:** `createOrder(OrderRequest request)`  
**Location:** `./stokr-oms/src/main/java/com/stokr/oms/service/OmsOrderService.java`

**What Exists:**
- ✅ Order is created in OMS
- ✅ Order record stored
- ✅ OMS has internal timestamps
- ❌ NOT linked back to strategy signal

**What's Missing:**
- ❌ Signal has no reference to OMS order creation time
- ❌ No linkage between signal and OMS order
- ❌ Cross-system latency unmeasured

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: oms_order_id (UUID)
Purpose: Foreign key to OMS order
Type: UUID (nullable)

Column: oms_order_created_time (timestamp with timezone)
Purpose: When OMS received and created the order
Type: Instant (nullable)

Column: oms_submission_time (timestamp with timezone)
Purpose: When OMS submitted order to broker
Type: Instant (nullable)
```

**Implementation Point:**
```
File: OmsOrderService.java
Method: createOrder()
Action: onOrderCreated callback/event:
        strategySignalService.linkOmsOrder(
            signalId,
            orderId,
            Instant.now()  // OMS creation time
        )

When submitting to broker:
        signalUpdateService.updateOmsSubmissionTime(
            signalId,
            Instant.now()
        )
```

**Latency Metrics:**
```
oms_creation_latency = oms_order_created_time - order_intent_dispatch_time
Expected: 10-50ms (OMS processing)

oms_broker_submission_latency = oms_submission_time - oms_order_created_time
Expected: 5-20ms (submission batching)

cumulative_to_oms_submit = oms_submission_time - market_acceleration_peak
Expected: 60-300 seconds
```

---

## STAGE 9: BROKER SUBMISSION

### Current State
**Class:** `BrokerIntegration`  
**Method:** `submitOrder(Order order)`  
**Location:** `./stokr-broker-integration/...` (exact class varies by broker)

**What Exists:**
- ✅ Order is sent to broker API
- ✅ Broker API call made
- ✅ Response received
- ❌ NOT captured at signal level

**What's Missing:**
- ❌ Broker submission timestamp
- ❌ Broker acknowledgment time
- ❌ Network latency measurement

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: broker_submit_time (timestamp with timezone)
Purpose: When request was sent to broker API
Type: Instant

Column: broker_ack_time (timestamp with timezone)
Purpose: When broker acknowledged receipt
Type: Instant

Column: broker_latency_ms (long)
Purpose: Roundtrip latency to broker (already exists, currently NULL)
Type: Long
```

**Implementation Point:**
```
File: BrokerIntegration.java (or wrapper)
Method: submitOrder()
Entry: submitTime = Instant.now()
       signalUpdateService.updateBrokerSubmitTime(signalId, submitTime)

Response:
       ackTime = Instant.now()
       latency = ackTime.toEpochMilli() - submitTime.toEpochMilli()
       signalUpdateService.updateBrokerAckAndLatency(
           signalId,
           ackTime,
           latency
       )
```

**Latency Metrics:**
```
broker_latency_ms = broker_ack_time - broker_submit_time
Expected: 50-500ms (network + API processing)

cumulative_to_broker_submit = broker_submit_time - market_acceleration_peak
Expected: 70-350 seconds
```

---

## STAGE 10: BROKER EXECUTION CONFIRMATION

### Current State
**Class:** `BrokerExecutionListener`  
**Method:** `onExecutionConfirmed(ExecutionEvent event)`  
**Location:** Message listener (RabbitMQ, WebSocket, etc.)

**What Exists:**
- ✅ Broker sends execution confirmation
- ✅ OutcomeTrackingService records fill
- ✅ `entry_price` is captured
- ❌ Execution timestamp not captured

**What's Missing:**
- ❌ Exact execution timestamp from broker
- ❌ Execution to position open latency

### Instrumentation Design

**Fields to Add to `StrategySignalEntity`:**
```
Column: broker_execution_time (timestamp with timezone)
Purpose: When broker confirmed the execution
Type: Instant (nullable)

Column: position_open_time (timestamp with timezone)
Purpose: When position was officially opened (same as broker_execution_time)
Type: Instant (nullable)

Column: execution_latency_ms (long) [ALREADY EXISTS]
Purpose: Roundtrip from signal creation to position open
Type: Long
```

**Implementation Point:**
```
File: BrokerExecutionListener.java
Method: onExecutionConfirmed()
Action: executionTime = extractFromBrokerMessage()
        signalUpdateService.recordExecution(
            signalId,
            executionTime,
            entryPrice
        )

Calculation:
        executionLatency = 
            Duration.between(created_at, executionTime).toMillis()
        signalEntity.setExecutionLatencyMs(executionLatency)
```

**Latency Metrics:**
```
broker_execution_latency = broker_execution_time - broker_ack_time
Expected: 100-500ms (trade matching + confirmation)

total_execution_latency = broker_execution_time - created_at
Expected: 200-1000ms (execution only, excluding pre-signal delays)

cumulative_pipeline_latency = broker_execution_time - market_acceleration_peak
Expected: 70-350 seconds (entire pipeline)
```

---

## SUMMARY: DATABASE SCHEMA ADDITIONS

### Fields to Add to `strategy_signals` Table

```sql
-- STAGE 1: Market Detection
ALTER TABLE strategy_signals ADD COLUMN scheduler_tick_time TIMESTAMP WITH TIME ZONE;

-- STAGE 2: Strategy Evaluation
ALTER TABLE strategy_signals ADD COLUMN evaluation_complete_time TIMESTAMP WITH TIME ZONE;

-- STAGE 3: Signal Detection
ALTER TABLE strategy_signals ADD COLUMN condition_detection_time TIMESTAMP WITH TIME ZONE;

-- STAGE 4: Confidence Calculation
ALTER TABLE strategy_signals ADD COLUMN confidence_calculation_start_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN confidence_calculation_end_time TIMESTAMP WITH TIME ZONE;

-- STAGE 5: Risk Evaluation
ALTER TABLE strategy_signals ADD COLUMN risk_queue_entry_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN risk_evaluation_start_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN risk_evaluation_end_time TIMESTAMP WITH TIME ZONE;

-- STAGE 6: Persistence
ALTER TABLE strategy_signals ADD COLUMN persistence_request_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN persistence_complete_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN cumulative_latency_to_persistence_ms BIGINT;

-- STAGE 7: Order Intent
ALTER TABLE strategy_signals ADD COLUMN order_intent_creation_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN order_intent_dispatch_time TIMESTAMP WITH TIME ZONE;

-- STAGE 8: OMS Order
ALTER TABLE strategy_signals ADD COLUMN oms_order_id UUID;
ALTER TABLE strategy_signals ADD COLUMN oms_order_created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN oms_submission_time TIMESTAMP WITH TIME ZONE;

-- STAGE 9: Broker Submit
ALTER TABLE strategy_signals ADD COLUMN broker_submit_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN broker_ack_time TIMESTAMP WITH TIME ZONE;
-- execution_latency_ms [ALREADY EXISTS - currently unused/NULL]
-- broker_latency_ms [ALREADY EXISTS - currently unused/NULL]

-- STAGE 10: Execution
ALTER TABLE strategy_signals ADD COLUMN broker_execution_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE strategy_signals ADD COLUMN position_open_time TIMESTAMP WITH TIME ZONE;
-- execution_latency_ms [POPULATE THIS FIELD]
```

---

## CALCULATED LATENCY FIELDS

```sql
-- Add computed columns for analysis
ALTER TABLE strategy_signals ADD COLUMN signal_detection_to_persistence_ms BIGINT;
ALTER TABLE strategy_signals ADD COLUMN persistence_to_broker_submit_ms BIGINT;
ALTER TABLE strategy_signals ADD COLUMN broker_submit_to_execution_ms BIGINT;
ALTER TABLE strategy_signals ADD COLUMN acceleration_peak_to_execution_ms BIGINT;

-- Trigger to populate computed columns on insert/update:
CREATE OR REPLACE FUNCTION calculate_latencies()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.persistence_complete_time IS NOT NULL AND 
     NEW.broker_submit_time IS NOT NULL THEN
    NEW.persistence_to_broker_submit_ms := 
      EXTRACT(EPOCH FROM (NEW.broker_submit_time - NEW.persistence_complete_time))::BIGINT * 1000;
  END IF;
  
  IF NEW.broker_submit_time IS NOT NULL AND 
     NEW.broker_execution_time IS NOT NULL THEN
    NEW.broker_submit_to_execution_ms := 
      EXTRACT(EPOCH FROM (NEW.broker_execution_time - NEW.broker_submit_time))::BIGINT * 1000;
  END IF;
  
  IF NEW.candle_timestamp IS NOT NULL AND 
     NEW.broker_execution_time IS NOT NULL THEN
    NEW.acceleration_peak_to_execution_ms := 
      EXTRACT(EPOCH FROM (NEW.broker_execution_time - NEW.candle_timestamp))::BIGINT * 1000;
  END IF;
  
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER latency_calculator
BEFORE INSERT OR UPDATE ON strategy_signals
FOR EACH ROW EXECUTE FUNCTION calculate_latencies();
```

---

## POST-IMPLEMENTATION QUERIES

### To Answer: "Was signal detected late?"

```sql
SELECT 
  symbol,
  scheduler_tick_time,
  condition_detection_time,
  evaluation_complete_time,
  EXTRACT(EPOCH FROM (evaluation_complete_time - condition_detection_time)) * 1000 
    AS detection_to_eval_ms,
  realized_pnl,
  CASE WHEN realized_pnl > 0 THEN 'WIN' ELSE 'LOSS' END as outcome
FROM strategy_signals
WHERE strategy_name = 'INDEX_HUNT'
ORDER BY created_at DESC;
```

### To Answer: "Was risk engine slow?"

```sql
SELECT 
  symbol,
  risk_queue_entry_time,
  risk_evaluation_start_time,
  risk_evaluation_end_time,
  EXTRACT(EPOCH FROM (risk_evaluation_start_time - risk_queue_entry_time)) * 1000 
    AS risk_queue_wait_ms,
  EXTRACT(EPOCH FROM (risk_evaluation_end_time - risk_evaluation_start_time)) * 1000 
    AS risk_eval_duration_ms,
  realized_pnl
FROM strategy_signals
WHERE strategy_name = 'INDEX_HUNT'
ORDER BY risk_queue_wait_ms DESC;
```

### To Answer: "Was OMS slow?"

```sql
SELECT 
  symbol,
  order_intent_dispatch_time,
  oms_order_created_time,
  oms_submission_time,
  EXTRACT(EPOCH FROM (oms_order_created_time - order_intent_dispatch_time)) * 1000 
    AS oms_creation_latency_ms,
  EXTRACT(EPOCH FROM (oms_submission_time - oms_order_created_time)) * 1000 
    AS oms_submit_latency_ms,
  realized_pnl
FROM strategy_signals
WHERE strategy_name = 'INDEX_HUNT'
ORDER BY oms_creation_latency_ms DESC;
```

### To Answer: "Was broker slow?"

```sql
SELECT 
  symbol,
  broker_submit_time,
  broker_ack_time,
  broker_execution_time,
  EXTRACT(EPOCH FROM (broker_ack_time - broker_submit_time)) * 1000 
    AS broker_ack_latency_ms,
  EXTRACT(EPOCH FROM (broker_execution_time - broker_ack_time)) * 1000 
    AS broker_exec_latency_ms,
  EXTRACT(EPOCH FROM (broker_execution_time - broker_submit_time)) * 1000 
    AS broker_total_latency_ms,
  realized_pnl
FROM strategy_signals
WHERE strategy_name = 'INDEX_HUNT'
ORDER BY broker_total_latency_ms DESC;
```

---

## IMPLEMENTATION CHECKLIST

- [ ] Add 20 new columns to strategy_signals table
- [ ] Create trigger to calculate derived latency fields
- [ ] Modify CatalogDrivenScanScheduler to capture scheduler_tick_time
- [ ] Modify StrategyRegistry to capture evaluation and detection times
- [ ] Modify ConfidenceEngineV2 to capture confidence calculation times
- [ ] Modify RiskEngineService to capture risk evaluation times
- [ ] Modify StrategySignalPipelineService to capture persistence times
- [ ] Modify OmsIntentDispatcher to capture intent creation/dispatch times
- [ ] Modify OmsOrderService to capture OMS order creation time
- [ ] Modify BrokerIntegration to capture broker submit/ack times
- [ ] Modify BrokerExecutionListener to capture execution time
- [ ] Create database views for latency analysis
- [ ] Add tests to verify timestamp population
- [ ] Monitor log volume impact
- [ ] Run analysis queries to validate hypothesis

---

## EXPECTED OUTCOMES

After implementation, we will be able to answer with **MEASURED EVIDENCE**:

✅ **Was signal detected late?** - Compare condition_detection_time to scheduler_tick_time  
✅ **Was risk engine slow?** - Measure risk_evaluation_start_time - risk_queue_entry_time  
✅ **Was OMS slow?** - Measure oms_submission_time - order_intent_dispatch_time  
✅ **Was broker slow?** - Measure broker_execution_time - broker_submit_time  
✅ **Which component is the bottleneck?** - Compare all latency segments  
✅ **Does latency correlate with poor outcomes?** - Join to realized_pnl  

---

**OBSERVABILITY PLAN COMPLETE - NO IMPLEMENTATION PERFORMED**

