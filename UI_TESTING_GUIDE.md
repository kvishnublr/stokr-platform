# UI Testing Guide - Unified Execution Framework

**Purpose**: Step-by-step instructions to test and verify all Phase 6/7 components from the UI  
**Time Required**: 30-45 minutes for full verification

## Prerequisites

### 1. Start the Application

```bash
# Navigate to project root
cd C:\Users\itsvi\Desktop\work_new\stokr-platform

# Build the application
mvn clean package -DskipTests

# Start the bootstrap service (runs all services)
java -jar stokr-bootstrap/target/stokr-bootstrap-1.0.0-SNAPSHOT.jar
```

### 2. Verify Services are Running

Wait for all services to start (watch console for "Started" messages):
- Port 8080: stokr-bootstrap (API Gateway)
- Port 8081-8094: Individual services

### 3. Open UI

Navigate to: `http://localhost:3000` (or configured UI port)

---

## Part 1: REST API Verification (Postman/curl)

### 1.1 Test Execution Mode Control

**Endpoint**: `GET /api/admin/execution/mode`

```bash
curl -X GET http://localhost:8080/api/admin/execution/mode \
  -H "Content-Type: application/json"
```

**Expected Response**:
```json
{
  "mode": "PAPER",
  "lastSwitchTime": "2026-05-23T10:40:00Z",
  "lastSwitchedBy": "SYSTEM",
  "availableModes": ["PAPER", "LIVE", "BOTH"]
}
```

### 1.2 Test Mode Switching (PAPER → LIVE)

**Endpoint**: `POST /api/admin/execution/mode/{newMode}`

```bash
curl -X POST http://localhost:8080/api/admin/execution/mode/LIVE \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Testing mode switch",
    "requestedBy": "TEST_USER"
  }'
```

**Expected Response**:
```json
{
  "mode": "LIVE",
  "lastSwitchTime": "2026-05-23T10:45:00Z",
  "lastSwitchedBy": "TEST_USER"
}
```

### 1.3 Test Health Check

**Endpoint**: `GET /api/admin/execution/health`

```bash
curl -X GET http://localhost:8080/api/admin/execution/health
```

**Expected Response**:
```json
{
  "overallStatus": "HEALTHY",
  "components": {
    "broker-adapter": "CONNECTED",
    "paper-exchange": "RUNNING",
    "matching-engine": "READY",
    "order-manager": "HEALTHY",
    "position-manager": "HEALTHY",
    "pnl-engine": "HEALTHY"
  },
  "latencies": {
    "broker-latency-p99-ms": 150,
    "market-feed-latency-p99-ms": 50,
    "match-latency-p99-ms": 5
  }
}
```

### 1.4 Test Execution Statistics

**Endpoint**: `GET /api/admin/execution/stats`

```bash
curl -X GET http://localhost:8080/api/admin/execution/stats
```

**Expected Response**:
```json
{
  "ordersToday": 1042,
  "ordersFilled": 987,
  "ordersRejected": 15,
  "avgFillLatencyMs": 45.3,
  "avgSlippageBps": 2.1,
  "marginUtilization": 0.65
}
```

### 1.5 Test Replay Start

**Endpoint**: `POST /api/admin/execution/replay/start`

```bash
curl -X POST http://localhost:8080/api/admin/execution/replay/start \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "SBIN",
    "startTime": "2025-01-01T09:15:00Z",
    "endTime": "2025-01-01T15:30:00Z",
    "speed": 1.0
  }'
```

**Expected Response**:
```json
{
  "status": "STARTED",
  "symbol": "SBIN",
  "startTime": "2025-01-01T09:15:00Z",
  "endTime": "2025-01-01T15:30:00Z",
  "speed": 1.0
}
```

### 1.6 Test Replay Status

**Endpoint**: `GET /api/admin/execution/replay/status`

```bash
curl -X GET http://localhost:8080/api/admin/execution/replay/status
```

**Expected Response**:
```json
{
  "status": "RUNNING",
  "currentTime": "2025-01-01T12:00:00Z",
  "progress": 0.45,
  "speed": 1.0
}
```

### 1.7 Test Configuration Access

**Endpoint**: `GET /api/admin/execution/config`

```bash
curl -X GET http://localhost:8080/api/admin/execution/config
```

**Expected Response**:
```json
{
  "mode": "PAPER",
  "paper": {
    "startingCapital": "1000000.00",
    "slippageBps": 2.0,
    "latencyMinMs": 5,
    "latencyMaxMs": 15,
    "marginMultiplier": 1.0
  },
  "replay": {
    "enabled": true,
    "defaultSpeed": 1.0,
    "minSpeed": 0.1,
    "maxSpeed": 10.0
  },
  "synthetic": {
    "enabled": true,
    "volatility": 0.02
  }
}
```

---

## Part 2: UI Component Testing

### 2.1 ExecutionModeSelector Component

**Navigate to**: Admin Dashboard → Execution Mode Control

**Test Steps**:

1. ✅ **Verify Current Mode Display**
   - Should show current mode (default: PAPER)
   - Should display last switch time and operator

2. ✅ **Test Mode Selection Dropdown**
   - Click dropdown
   - Verify options: PAPER, LIVE, BOTH (Hybrid)
   - Each option shows description and risk level

3. ✅ **Test PAPER → LIVE Switch with Confirmation**
   - Select LIVE mode
   - Verify confirmation dialog appears
   - Warning message about real funds
   - Buttons: "I understand, proceed" and "Cancel"

4. ✅ **Test Mode Switch with Reason**
   - Enter reason: "Initial testing"
   - Click "Switch Mode"
   - Verify mode changes
   - Verify last switch time updates
   - Check audit trail logged

5. ✅ **Test Error Handling**
   - Try switching without entering reason
   - Should show validation error
   - Confirm button should be disabled

**Expected UI Elements**:
```
┌─ Execution Mode Control ──────────────────┐
│ Current Mode:     [PAPER]                 │
│ Last Switch:      2026-05-23 10:40:00     │
│ Switched By:      SYSTEM                  │
│                                            │
│ Target Mode:      [Dropdown ▼]            │
│ Reason:           [Text input]            │
│ [Switch Mode]  [Clear]                    │
│                                            │
│ ⚠️ WARNING: LIVE mode uses real funds     │
│ [I understand, proceed] [Cancel]          │
└────────────────────────────────────────────┘
```

### 2.2 ReplayControlsPanel Component

**Navigate to**: Admin Dashboard → Replay Controls

**Test Steps**:

1. ✅ **Setup Replay Configuration**
   - Symbol input: Type "SBIN"
   - Start Date: Select "2025-01-01"
   - End Date: Select "2025-01-31"
   - Speed: Use slider or preset buttons (1x, 2x, 5x, 10x)

2. ✅ **Start Replay**
   - Click "Start Replay" button
   - Verify status changes to "RUNNING"
   - Progress bar should appear
   - Current time should update

3. ✅ **Test Pause/Resume**
   - Click "Pause" button while running
   - Status should change to "PAUSED"
   - Progress bar should freeze
   - Click "Resume"
   - Progress should continue

4. ✅ **Test Speed Control**
   - While running, change speed to 5x
   - Progress should advance faster
   - Speed display should update
   - Verify candles process at faster rate

5. ✅ **Test Stop**
   - Click "Stop" button
   - Status should change to "STOPPED"
   - Controls should re-enable
   - Should be able to start new replay

**Expected UI Elements**:
```
┌─ Replay Controls ─────────────────────────┐
│ Setup Replay:                              │
│ Symbol:        [SBIN          ]           │
│ Start Date:    [2025-01-01    ]           │
│ End Date:      [2025-01-31    ]           │
│                                            │
│ Speed:         [====●────]  1.0x          │
│ [0.5x] [1x] [2x] [5x] [10x]              │
│ [Start Replay]                             │
│                                            │
│ Replay Status:                             │
│ Status: RUNNING                            │
│ [████████░░░░░░░] 45%                     │
│ Current: 2025-01-15 12:00:00              │
│ Speed: 1.0x                                │
│ [Pause] [Stop]                             │
└────────────────────────────────────────────┘
```

### 2.3 MarketDataCoverageMonitor Component

**Navigate to**: Admin Dashboard → Market Data Coverage

**Test Steps**:

1. ✅ **Verify Coverage Display**
   - Should list symbols with data availability
   - Show timeframes (1m, 5m, 15m, 1h, 1d)
   - Display date ranges
   - Show candle count per symbol

2. ✅ **Check Coverage Status**
   - Green "Complete" badge for full coverage
   - Yellow "Partial" badge for gaps
   - Coverage percentage at top

3. ✅ **Alert for Partial Coverage**
   - Symbols with gaps should show yellow alert
   - Message: "Insufficient data for certain timeframes"

**Expected Symbols**:
```
Coverage Summary: 2/4 Complete (50%)

SBIN
├─ Timeframes: 1m, 5m, 15m, 1h, 1d ✅
├─ Range: 2024-01-01 to 2025-12-31
├─ Candles: 247,680
└─ Status: ✅ COMPLETE

RELIANCE  
├─ Timeframes: 1m, 5m, 15m ⚠️
├─ Range: 2024-06-01 to 2025-12-31
├─ Candles: 98,760
└─ Status: ⚠️ PARTIAL
   (Missing 1h, 1d data)
```

### 2.4 ExecutionStatsPanel Component

**Navigate to**: Admin Dashboard → Execution Statistics

**Test Steps**:

1. ✅ **Verify Statistics Cards**
   - Total Orders: Shows count
   - Filled: Shows count and percentage
   - Pending: Shows count and percentage
   - Rejected: Shows count and percentage

2. ✅ **Check Progress Bars**
   - Fill rate bar (green)
   - Rejection rate bar (red)
   - Pending rate bar (yellow)
   - All should sum to 100%

3. ✅ **Performance Metrics**
   - Average Fill Latency: Should show ms value
   - Average Slippage: Should show bps value
   - Margin Utilization: Should show percentage

4. ✅ **Auto-Refresh**
   - Watch stats update every 5 seconds
   - Verify values change if orders being placed

**Expected Display**:
```
┌─ Execution Statistics ────────────────────┐
│ Total: 1042  Filled: 987  Pending: 40     │
│ Rejected: 15                               │
│                                            │
│ Order Status:                              │
│ Filled:    [████████░░] 94.7%             │
│ Rejected:  [█░░░░░░░░░] 1.4%              │
│ Pending:   [█░░░░░░░░░] 3.8%              │
│                                            │
│ Performance:                               │
│ ┌─────────────┐ ┌──────────┐ ┌──────────┐│
│ │Avg Latency  │ │Avg Slip  │ │Margin   ││
│ │  45.3 ms    │ │  2.1 bps │ │  65.0%  ││
│ └─────────────┘ └──────────┘ └──────────┘│
└────────────────────────────────────────────┘
```

---

## Part 3: WebSocket Real-Time Updates Testing

### 3.1 Test Order Updates

**Steps**:
1. Open browser DevTools (F12)
2. Navigate to Console tab
3. Place a test order via API or UI
4. Watch for WebSocket messages:

**Expected Console Output**:
```javascript
[Realtime] Connected to WebSocket
[Realtime] SUBSCRIBE on orders: {userId: "user-123"}
[Realtime] ORDER_CREATED on orders: {orderId: "order-456", symbol: "SBIN", quantity: 10}
[Realtime] ORDER_ACCEPTED on orders: {orderId: "order-456", status: "ACCEPTED"}
[Realtime] ORDER_FILLED on orders: {orderId: "order-456", status: "FILLED", avgPrice: 500.25}
```

### 3.2 Test Position Updates

**Steps**:
1. Monitor position cards on trader terminal
2. Place orders that create positions
3. Verify position details update in real-time

**Expected Updates**:
- Position opens → quantity increases
- Position MTM updates on every tick
- Unrealized PnL updates
- Equity value updates

### 3.3 Test PnL Updates

**Steps**:
1. Navigate to PnL Dashboard
2. Monitor PnL values while replay/trading active
3. Verify real-time updates

**Expected Updates**:
```json
{
  "unrealizedPnL": 1234.56,
  "realizedPnL": 567.89,
  "totalPnL": 1802.45,
  "equityValue": 1001802.45,
  "marginUtilization": 0.65
}
```

---

## Part 4: Integration Testing Workflow

### 4.1 Complete Order Lifecycle Test

**Test Scenario**: Place order → Monitor execution → Verify position → Check PnL

**Steps**:

1. **Setup**
   - Switch to PAPER mode
   - Verify starting capital (1M)

2. **Place Order**
   - Submit test order: BUY 10 SBIN @ 500
   - Verify order appears in OMS

3. **Monitor Execution**
   - Watch order status in UI
   - States: CREATED → VALIDATED → SUBMITTED → ACCEPTED → FILLED
   - Verify fill price, quantity in real-time

4. **Check Position**
   - Verify position created
   - Quantity: 10
   - Entry price: ~500 (with slippage)
   - MTM updates on each tick

5. **Check PnL**
   - Unrealized PnL shows current profit/loss
   - Equity updates
   - Margin utilization increases

6. **Close Position**
   - Place opposite order (SELL 10 SBIN)
   - Verify position closes
   - Realized PnL recorded

### 4.2 Replay Testing Workflow

**Test Scenario**: Run historical replay and verify order execution

**Steps**:

1. **Start Replay**
   - Symbol: SBIN
   - Date: 2025-01-01 to 2025-01-10
   - Speed: 2x (faster testing)

2. **Monitor Replay Progress**
   - Progress bar advances
   - Current time updates
   - Speed indicator shows 2.0x

3. **Place Orders During Replay**
   - Submit orders while replay running
   - Orders should execute at replay prices
   - Fills based on synthetic/historical data

4. **Verify Deterministic Results**
   - Run same replay twice
   - Results should be identical
   - Same fill prices, quantities, times

5. **Stop and Verify**
   - Click Stop
   - Verify all positions closed
   - Check final PnL matches expected

### 4.3 Safety Guard Testing

**Test Scenario**: Verify isolation between PAPER and LIVE

**Steps**:

1. **PAPER Mode Test**
   - Place order in PAPER mode
   - Verify NO broker API calls
   - Verify paper exchange provides fills
   - Check order status in UI

2. **Mode Switch to LIVE**
   - Switch to LIVE mode
   - Place order
   - Verify broker API is called
   - Monitor broker response

3. **Hybrid Mode Test**
   - Switch to BOTH (Hybrid)
   - Place order
   - Verify order executes BOTH:
     - Paper exchange (fills)
     - Broker API (real submission)
   - Watch divergence metrics

---

## Part 5: Configuration Testing

### 5.1 Update Paper Trading Parameters

**API Endpoint**: `POST /api/admin/execution/config/paper/slippage`

```bash
curl -X POST http://localhost:8080/api/admin/execution/config/paper/slippage \
  -H "Content-Type: application/json" \
  -d '{
    "slippageBps": 5.0,
    "reason": "Testing higher slippage"
  }'
```

**Verify**:
- Slippage updates immediately
- New fills show 5.0 bps slippage (was 2.0)
- No service restart needed

### 5.2 Update Replay Speed Range

**API Endpoint**: `POST /api/admin/execution/config/replay/speed`

```bash
curl -X POST http://localhost:8080/api/admin/execution/config/replay/speed \
  -H "Content-Type: application/json" \
  -d '{
    "speed": 5.0,
    "reason": "Speed up testing"
  }'
```

**Verify**:
- Default replay speed changes to 5.0x
- Next replay starts at 5.0x
- UI slider updates

### 5.3 View Configuration Audit Trail

**API Endpoint**: `GET /api/admin/execution/config/audit`

```bash
curl -X GET http://localhost:8080/api/admin/execution/config/audit
```

**Expected Response**:
```json
[
  {
    "parameter": "PAPER_SLIPPAGE",
    "oldValue": "2.0",
    "newValue": "5.0",
    "reason": "Testing higher slippage",
    "timestamp": "2026-05-23T10:50:00Z",
    "changedBy": "ADMIN"
  },
  {
    "parameter": "REPLAY_SPEED",
    "oldValue": "1.0",
    "newValue": "5.0",
    "reason": "Speed up testing",
    "timestamp": "2026-05-23T10:51:00Z",
    "changedBy": "ADMIN"
  }
]
```

---

## Part 6: Performance Verification

### 6.1 Order Matching Latency

**Test**: Place 100 orders rapidly in PAPER mode

**Verify**:
- Average fill latency < 1ms
- P99 latency < 5ms
- No orders dropped

### 6.2 WebSocket Message Latency

**Test**: Monitor WebSocket message arrival time

**Verify**:
- Order update messages < 10ms
- Position MTM updates < 10ms
- PnL updates < 10ms

### 6.3 Replay Performance

**Test**: Run 1 month of replay at 10x speed

**Verify**:
- Can process ~100 candles per second
- No memory leaks
- UI remains responsive

---

## Testing Checklist

### REST APIs ✅
- [ ] GET /api/admin/execution/mode
- [ ] POST /api/admin/execution/mode/{newMode}
- [ ] GET /api/admin/execution/health
- [ ] GET /api/admin/execution/stats
- [ ] POST /api/admin/execution/replay/start
- [ ] POST /api/admin/execution/replay/pause
- [ ] POST /api/admin/execution/replay/resume
- [ ] POST /api/admin/execution/replay/stop
- [ ] GET /api/admin/execution/replay/status
- [ ] GET /api/admin/execution/config
- [ ] POST /api/admin/execution/config/paper/*
- [ ] POST /api/admin/execution/config/replay/*
- [ ] GET /api/admin/execution/config/audit

### UI Components ✅
- [ ] ExecutionModeSelector (PAPER/LIVE/BOTH)
- [ ] ReplayControlsPanel (start/pause/resume/stop)
- [ ] MarketDataCoverageMonitor (symbols, timeframes)
- [ ] ExecutionStatsPanel (orders, fills, latency)

### Real-Time Updates ✅
- [ ] Order lifecycle updates via WebSocket
- [ ] Position MTM updates on ticks
- [ ] PnL snapshot broadcasts
- [ ] Signal injection feedback

### Integration Flows ✅
- [ ] Complete order lifecycle (place → execute → position → close)
- [ ] Replay mode execution
- [ ] Mode switching (PAPER → LIVE → BOTH)
- [ ] Safety isolation (PAPER ≠ LIVE fills)
- [ ] Configuration updates at runtime

### Performance ✅
- [ ] Order matching < 1ms
- [ ] WebSocket latency < 10ms
- [ ] Replay 10x speed sustainable
- [ ] No memory leaks under load

---

## Troubleshooting

### Issue: REST API Returns 404

**Solution**:
- Verify all services started
- Check if API gateway is running on port 8080
- Check if request URL is correct
- Verify module paths in bootstrap configuration

### Issue: WebSocket Doesn't Connect

**Solution**:
- Check if stokr-websocket service running
- Verify WebSocket port (usually 8085)
- Check browser console for connection errors
- Verify firewall allows WebSocket

### Issue: Replay Doesn't Start

**Solution**:
- Verify ReplayCoordinator bean initialized
- Check if historical data exists for symbol
- Verify date range is valid
- Check application logs for errors

### Issue: Mode Switch Fails

**Solution**:
- Verify ExecutionModeService injected
- Check if current mode validation passes
- Verify ExecutionMode enum has BOTH value
- Check application logs

---

## Success Criteria

✅ All tests pass when:
1. All REST endpoints return correct responses
2. All UI components render without errors
3. Real-time updates arrive < 100ms
4. Order execution completes in PAPER mode
5. Mode switching works for all transitions
6. Replay executes at configured speeds
7. Configuration updates apply immediately
8. WebSocket maintains connection
9. No console errors in browser
10. Performance meets latency targets

---

**Test Duration**: 30-45 minutes for complete verification  
**Test Report Template**: Copy testing checklist and mark each item as ✅ PASS or ❌ FAIL
