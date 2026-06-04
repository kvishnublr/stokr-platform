# Signal Execution Tracking - Integration Guide

## Overview
Complete end-to-end signal tracking from generation → trader terminal → broker execution.

## Components Created

### 1. **SignalExecutionTrack Entity** 
Database table to track every signal's journey:
- Signal ID, User ID, Strategy, Symbol
- Order ID, Broker Order ID
- Execution Status (GENERATED → DISPATCHED → ORDER_CREATED → SUBMITTED → ACCEPTED → FILLED)
- Broker Vendor (ZERODHA, etc.)
- Retry tracking with automatic fallback modes
- Execution timeline with latency metrics

### 2. **SignalExecutionTrackingService**
Core service managing signal tracking lifecycle:
- `initializeTrack()` - Start tracking when signal generated
- `recordStep()` - Log each execution step
- `recordOrderCreated()` - When OMS order created
- `recordBrokerSubmission()` - When submitted to broker
- `recordBrokerAccepted()` - When broker accepts
- `recordFilled()` - When trade execution completes
- `recordFailure()` - When any step fails
- `recordRetry()` - When retrying with fallback mode

### 3. **SignalExecutionFallbackService**
Automatic retry with fallback execution modes:
- Runs every 5 minutes to find failed signals
- Fallback chain: **LIVE → BOTH → PAPER**
- Max 3 retry attempts per signal
- 2-minute wait between retries
- Ensures signals execute in some mode rather than silently fail

### 4. **SignalExecutionDashboardController** 
REST APIs for UI to track signals:

#### GET `/api/trader/signals/{signalId}/track`
**Real-time signal tracking with complete status:**
```json
{
  "signalId": "uuid",
  "strategyKey": "VWAP_BOUNCE",
  "symbol": "INFY",
  "signalType": "BUY",
  "status": "FILLED",
  "executionMode": "LIVE",
  "broker": {
    "vendor": "ZERODHA",
    "brokerOrderId": "220512001234"
  },
  "order": {
    "orderId": "uuid",
    "side": "BUY",
    "quantity": "50",
    "entryPrice": "1505.50"
  },
  "execution": {
    "currentStep": "Trade execution completed",
    "executionTimeMs": 2850,
    "createdAt": "2026-06-04T13:15:00Z",
    "filledAt": "2026-06-04T13:15:02.85Z"
  },
  "failure": null
}
```

#### GET `/api/trader/signals/history`
**Last 24 hours signal execution history:**
- Page through signals
- See status progression
- Track execution times
- Identify failure patterns

#### GET `/api/trader/signals/dashboard`
**Execution statistics & health:**
- 24h & 7d success rates
- Pending signal count
- Retryable signals count
- Broker connection status

#### GET `/api/trader/signals/pending`
**Signals waiting for execution:**
- Filter by strategy
- Show wait time
- Identify stuck signals

#### GET `/api/trader/signals/issues`
**Signals requiring manual intervention:**
- Failed signals only
- Failure reason
- Auto-generated fix suggestions
- Contact support option

---

## Integration Steps

### Step 1: Inject TrackingService into OrderIntentProcessor

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIntentProcessor {
    
    private final SignalExecutionTrackingService trackingService;
    // ... other dependencies
    
    @Transactional
    public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
        // Initialize tracking at start
        trackingService.initializeTrack(signal, userId);
        
        // ... rest of processing
    }
}
```

### Step 2: Record Each Execution Step

```java
// After strategy validation
if (defOpt.isEmpty()) {
    trackingService.recordFailure(signal.getId(), userId, 
        SignalExecutionTrack.SignalExecutionStatus.VALIDATION_FAILED,
        "STRATEGY_NOT_FOUND", sigStrategyKey);
    return;
}

// After execution mode resolution
trackingService.recordStep(signal.getId(), userId,
    "Execution mode: " + mode.name(),
    SignalExecutionTrack.SignalExecutionStatus.DISPATCHED);

// After sizing resolution  
trackingService.recordStep(signal.getId(), userId,
    "Position sizing: " + sizing.normalizedQuantity() + " shares",
    SignalExecutionTrack.SignalExecutionStatus.ORDER_CREATED);

// After risk check passes
trackingService.recordStep(signal.getId(), userId,
    "Risk check passed",
    SignalExecutionTrack.SignalExecutionStatus.RISK_CHECK);

// After order creation
trackingService.recordOrderCreated(signal.getId(), userId,
    order.getId(), mode.name(), order.getSide(), order.getQuantity());

// When submitted to broker
trackingService.recordBrokerSubmission(signal.getId(), userId,
    brokerOrderId, order.getBrokerVendor());
```

### Step 3: Record Completion

```java
// In execution adapter when filled
trackingService.recordFilled(signal.getId(), userId);

// Or on failure
trackingService.recordFailure(signal.getId(), userId,
    SignalExecutionTrack.SignalExecutionStatus.RISK_FAILED,
    decision.reasonCode(), decision.message());
```

### Step 4: Enable Automatic Retry Scheduler

```yaml
# application.properties
stokr.execution.fallback.retry-interval-ms=300000  # 5 minutes
stokr.execution.fallback.max-retries=3
stokr.execution.fallback.retry-after-minutes=2
```

### Step 5: Run Database Migration

```sql
CREATE TABLE signal_execution_tracks (
    id UUID PRIMARY KEY,
    signal_id UUID NOT NULL,
    user_id UUID NOT NULL,
    strategy_key VARCHAR(255) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    signal_type VARCHAR(50),
    order_id UUID,
    broker_order_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    execution_mode VARCHAR(50),
    broker_vendor VARCHAR(100),
    side VARCHAR(10),
    quantity DECIMAL,
    entry_price DECIMAL,
    current_step VARCHAR(500),
    last_error VARCHAR(2000),
    failure_reason VARCHAR(500),
    retry_count INT DEFAULT 0,
    last_retry_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    executed_at TIMESTAMP,
    filled_at TIMESTAMP,
    execution_time_ms BIGINT,
    metadata JSONB,
    FOREIGN KEY (signal_id) REFERENCES strategy_signals(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_signal_id ON signal_execution_tracks(signal_id);
CREATE INDEX idx_user_id ON signal_execution_tracks(user_id);
CREATE INDEX idx_status ON signal_execution_tracks(status);
CREATE INDEX idx_created_at ON signal_execution_tracks(created_at);
```

---

## Execution Flow with Tracking

```
Signal Generated (BUY 50 INFY at 1505.00)
    ↓ [GENERATED]
    └─ initializeTrack()

Signal Dispatched to Trader Terminal
    ↓ [DISPATCHED]
    └─ recordStep()

Strategy Validation
    ✓ INFY exists
    ✓ VWAP_BOUNCE enabled
    ↓ [VALIDATION_PASSED]
    └─ recordStep()

Execution Mode Resolution
    Mode: LIVE (from trader preference)
    ↓ [MODE_RESOLVED]
    └─ recordStep()

Position Sizing
    Quantity: 50 (normalized)
    Available Capital: ✓
    ↓ [SIZING_OK]
    └─ recordStep()

Safety Gate Check
    Kill Switch: OFF
    Market Hours: YES
    Live Armed: YES
    ↓ [SAFETY_OK]
    └─ recordStep()

Risk Engine
    Max Loss: ✓
    Margin: ✓
    Exposure: ✓
    ↓ [RISK_OK]
    └─ recordStep()

Broker Truth Validation
    Position: VERIFIED
    Limits: CLEAR
    ↓ [VALIDATION_OK]
    └─ recordStep()

Order Created in OMS
    OrderID: 550e8400-e29b-41d4-a716-446655440000
    State: CREATED
    ↓ [ORDER_CREATED]
    └─ recordOrderCreated()

Submitted to Broker (ZERODHA)
    BrokerOrderID: 220512001234
    ↓ [SUBMITTED]
    └─ recordBrokerSubmission()

Broker Accepted
    Entry Price: 1505.50
    ↓ [ACCEPTED]
    └─ recordBrokerAccepted()

Trade Execution Complete
    Filled: 50 @ 1505.50
    Execution Time: 2.85 seconds
    ↓ [FILLED]
    └─ recordFilled()

✅ Signal Successfully Executed
```

---

## Fallback Retry Mechanism

When a signal fails at any gate:

```
Initial Attempt: LIVE Mode
    ✗ FAILS: "Live gate not eligible"
    
Auto-Retry #1 (2 min later): Same LIVE Mode
    ✗ FAILS: "Still not eligible"
    
Auto-Retry #2 (4 min total): Fallback to BOTH Mode
    ✓ SUCCESS: Order created in BOTH mode
```

---

## UI Flow Components

### Real-time Signal Tracker
```
┌─────────────────────────────────────┐
│ INFY | BUY 50 | LIVE | ZERODHA     │
├─────────────────────────────────────┤
│ ✓ Generated       13:15:00.000      │
│ ✓ Dispatched      13:15:00.050      │
│ ✓ Validated       13:15:00.100      │
│ ✓ Risk Check      13:15:00.500      │
│ ✓ Order Created   13:15:00.800      │
│ ✓ Submitted       13:15:00.900      │
│   → ZerodhaID: 220512001234         │
│ ✓ Accepted        13:15:01.200      │
│ ✓ Filled @ 1505   13:15:02.850      │
│                                      │
│ Execution Time: 2.85 seconds        │
│ Status: COMPLETED ✓                 │
└─────────────────────────────────────┘
```

### Dashboard View
```
┌─────────────────────────────────────┐
│ Signal Execution Dashboard          │
├─────────────────────────────────────┤
│ Last 24 Hours:                      │
│  • Filled: 234 signals              │
│  • Failed: 8 signals                │
│  • Success Rate: 96.7%              │
│                                      │
│ Pending Execution:                  │
│  • Count: 3 signals                 │
│  • Strategies: VWAP_BOUNCE, NSE_X   │
│                                      │
│ Auto-Retry Status:                  │
│  • Retryable Signals: 2             │
│  • Status: RETRY SCHEDULED          │
│                                      │
│ Broker Status:                      │
│  • Primary: ZERODHA                 │
│  • Connection: LIVE ✓               │
│  • Last Sync: 2 seconds ago         │
└─────────────────────────────────────┘
```

---

## Broker Information

### ZERODHA (Primary)
- **API**: Kite API
- **Account Types**: MIS (intraday), CNC (delivery)
- **Order Types**: MARKET, LIMIT, STOP_LOSS
- **Execution**: Real-time to NSE/BSE/MCX
- **Status**: Live connection maintained

---

## Benefits

✅ **Complete Visibility**: Every step from signal to execution  
✅ **Auto Recovery**: Automatic fallback modes for failed signals  
✅ **Real-time Dashboard**: Live tracking UI  
✅ **Zero Silent Failures**: All failures logged and retried  
✅ **Performance Metrics**: Execution latency per signal  
✅ **Audit Trail**: Full history for compliance  
✅ **Broker Transparency**: Who executed, when, where  

---

## Next Steps

1. Create database migration (Flyway V89)
2. Inject `SignalExecutionTrackingService` into `OrderIntentProcessor`
3. Add tracking calls at each guard/step
4. Deploy `SignalExecutionFallbackService` scheduler
5. Add UI dashboard routes
6. Monitor retry success rates
7. Adjust retry intervals based on failure patterns

