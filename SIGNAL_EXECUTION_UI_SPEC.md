# Signal Execution UI - Live Tracking Dashboard

## Overview
Real-time visualization of signal execution flow from generation through broker execution.

---

## 1. Real-Time Signal Tracker (Single Signal View)

### URL: `/trader/signals/{signalId}`

### Layout
```
┌────────────────────────────────────────────────────────────────┐
│                   SIGNAL EXECUTION TRACKER                     │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  Signal: BUY 50 INFY @ Market   │ Strategy: VWAP_BOUNCE      │
│  Status: FILLED ✓               │ Mode: LIVE                 │
│  Broker: ZERODHA                │ Generated: 13:15:00.000    │
│                                                                │
├─────────────────────── EXECUTION FLOW ──────────────────────┤
│                                                                │
│  Step 1: SIGNAL GENERATED                       ✓ 13:15:00   │
│          └─ Buy signal from VWAP_BOUNCE                      │
│                                                                │
│  Step 2: STRATEGY VALIDATION                    ✓ 13:15:00   │
│          └─ VWAP_BOUNCE enabled ✓                            │
│          └─ INFY in universe ✓                               │
│                                                                │
│  Step 3: EXECUTION MODE RESOLUTION              ✓ 13:15:00   │
│          └─ Requested: LIVE                                  │
│          └─ Trader Preference: LIVE                          │
│          └─ Effective Mode: LIVE ✓                           │
│                                                                │
│  Step 4: POSITION SIZING                        ✓ 13:15:00   │
│          └─ Base Quantity: 50 shares                         │
│          └─ Available Capital: ₹75,000                       │
│          └─ Capital Required: ₹75,275 (50 @ 1505.50)        │
│          └─ Sizing Status: APPROVED ✓                        │
│                                                                │
│  Step 5: SAFETY GATE CHECK                      ✓ 13:15:00   │
│          └─ Kill Switch: OFF ✓                               │
│          └─ Market Hours: 13:15 (NSE Open) ✓                │
│          └─ Live Armed: YES ✓                                │
│                                                                │
│  Step 6: RISK ENGINE EVALUATION                 ✓ 13:15:00   │
│          └─ Max Daily Loss: ₹50,000 vs ₹32,500 used ✓       │
│          └─ Per-Position Limit: ₹100,000 ✓                   │
│          └─ Margin Available: ₹150,000 ✓                     │
│          └─ Risk Approved ✓                                  │
│                                                                │
│  Step 7: BROKER TRUTH VALIDATION                ✓ 13:15:00   │
│          └─ Current Holdings (from ZERODHA): 0 shares        │
│          └─ Sync Time: 13:14:59 (1 sec old)                 │
│          └─ Position Limit Check: PASS ✓                     │
│                                                                │
│  Step 8: ORDER CREATED IN OMS                   ✓ 13:15:00   │
│          └─ Order ID: 550e8400-e29b-41d4-a716               │
│          └─ State: CREATED → VALIDATED → RISK_CHECK         │
│          └─ Capital Reserved: ₹75,275                        │
│                                                                │
│  Step 9: SUBMITTED TO BROKER (ZERODHA)         ✓ 13:15:00   │
│          └─ Broker Order ID: 220512001234                    │
│          └─ Time Submitted: 13:15:00.900                     │
│          └─ Submission Latency: 900ms                        │
│                                                                │
│  Step 10: BROKER ACCEPTED                       ✓ 13:15:01   │
│           └─ Broker Response: ORDER_ACCEPTED                 │
│           └─ Entry Price: 1505.50                            │
│           └─ Time Accepted: 13:15:01.200                     │
│           └─ Acceptance Latency: 300ms                       │
│                                                                │
│  Step 11: TRADE EXECUTION COMPLETED             ✓ 13:15:02   │
│           └─ Filled: 50 shares @ 1505.50                     │
│           └─ Total Value: ₹75,275                            │
│           └─ Fill Time: 13:15:02.850                         │
│           └─ Fill Latency: 1650ms                            │
│           └─ Total Execution Time: 2.85 seconds              │
│                                                                │
│  ✅ SIGNAL EXECUTION COMPLETED SUCCESSFULLY                  │
│                                                                │
├──────────────────────────────────────────────────────────────┤
│  KEY METRICS                                                  │
├──────────────────────────────────────────────────────────────┤
│  • Total Execution Time: 2.85 seconds                        │
│  • Dispatch Latency: 900ms (signal to broker)               │
│  • Broker Processing Time: 1.95 seconds                      │
│  • Success Rate: 100% (0 retries)                           │
│                                                                │
├──────────────────────────────────────────────────────────────┤
│  BROKER INFORMATION                                           │
├──────────────────────────────────────────────────────────────┤
│  • Vendor: ZERODHA (Kite API)                                │
│  • Broker Order ID: 220512001234                             │
│  • Connection Status: LIVE ✓                                 │
│  • Last Sync: 2 seconds ago                                  │
│  • Account: MIS (Intraday)                                   │
│  • Execution Venue: NSE                                      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. Dashboard - Execution Summary (All Signals)

### URL: `/trader/dashboard`

### Layout
```
┌────────────────────────────────────────────────────────────────┐
│              SIGNAL EXECUTION DASHBOARD                        │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  📊 PERFORMANCE METRICS                                       │
│  ├─ Last 24 Hours: 234 Filled | 8 Failed | 96.7% Success    │
│  ├─ Last 7 Days:   1,245 Filled | 42 Failed | 96.7% Success │
│  └─ Average Latency: 2.3 seconds                             │
│                                                                │
│  ⏳ PENDING EXECUTION                                          │
│  ├─ Waiting for Broker: 3 signals                            │
│  │  ├─ VWAP_BOUNCE: 2 signals (waiting 45 sec)             │
│  │  ├─ NSE_SPIKE: 1 signal (waiting 20 sec)                │
│  │  └─ [VIEW PENDING SIGNALS]                               │
│  └─ Auto-Retry Enabled: YES ✓                                │
│                                                                │
│  🔄 AUTO-RETRY STATUS                                         │
│  ├─ Retryable Failed Signals: 2                              │
│  │  ├─ Reason: Live gate not eligible                       │
│  │  ├─ Next Retry: In 30 seconds                            │
│  │  ├─ Fallback Mode: BOTH (2nd attempt)                    │
│  │  └─ [VIEW RETRYING SIGNALS]                              │
│  └─ Retry Success Rate: 87.5% (7/8 recovered)              │
│                                                                │
│  🚨 ISSUES REQUIRING ACTION                                   │
│  ├─ Failed Signals (24h): 8                                  │
│  │  ├─ Risk Rejected: 3                                      │
│  │  ├─ Exposure Exceeded: 2                                  │
│  │  ├─ Live Gate: 2                                          │
│  │  ├─ Broker Disconnect: 1                                  │
│  │  └─ [VIEW ISSUES] [AUTO-RETRY SCHEDULED]                │
│  └─ Manual Intervention: 0                                   │
│                                                                │
│  🔗 BROKER STATUS                                             │
│  ├─ Primary Broker: ZERODHA                                  │
│  │  ├─ Connection: LIVE ✓ (since 13:05)                    │
│  │  ├─ Account: MIS (INTRADAY)                             │
│  │  ├─ Available Balance: ₹2,45,000                         │
│  │  ├─ Used Margin: ₹75,275                                │
│  │  ├─ Last Sync: 2 seconds ago                            │
│  │  └─ Orders Today: 234                                    │
│  └─ Fallback Broker: SIM (Paper)                            │
│     └─ Status: Ready (used for fallback on connection loss)  │
│                                                                │
│  📈 EXECUTION QUALITY                                        │
│  ├─ Average Latency: 2.3 seconds                            │
│  ├─ P95 Latency: 4.2 seconds                                │
│  ├─ P99 Latency: 5.8 seconds                                │
│  ├─ Slippage (Avg): ₹2.45 per share                        │
│  └─ Fill Rate: 99.8% (one partial fill)                    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 3. Signal History - Time Series View

### URL: `/trader/signals/history`

### Layout
```
┌────────────────────────────────────────────────────────────────┐
│              SIGNAL EXECUTION HISTORY (24h)                   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ 234 signals total  |  Filter: All  | Sort: Recent First       │
│                                                                │
├─ Signal #234 ─────────────────────────────────────────────────┤
│ BUY 50 INFY @ Market    | VWAP_BOUNCE   | 13:15  | ✓ FILLED  │
│ Broker: ZERODHA (220512001234)  | Mode: LIVE                 │
│ Entry: 1505.50  | Time: 2.85s  | Retries: 0                 │
│ [Expand] [Details] [Retry] [Cancel]                          │
│                                                                │
├─ Signal #233 ─────────────────────────────────────────────────┤
│ SELL 30 TCS @ Market    | NSE_SPIKE     | 13:12  | ✓ FILLED  │
│ Broker: ZERODHA (220512001233)  | Mode: LIVE                 │
│ Entry: 3845.25  | Time: 1.92s  | Retries: 0                 │
│ [Expand] [Details]                                            │
│                                                                │
├─ Signal #232 ─────────────────────────────────────────────────┤
│ BUY 40 RELIANCE @ Market | ADV_CASH     | 13:09  | ⏳ PENDING │
│ Broker: ZERODHA (...)    | Mode: LIVE                         │
│ Waiting since: 45 seconds  | Status: SUBMITTED              │
│ [Expand] [Details] [Cancel]                                  │
│                                                                │
├─ Signal #231 ─────────────────────────────────────────────────┤
│ SELL 25 HDFC @ Market   | VWAP_BOUNCE   | 13:06  | ⚠ FAILED  │
│ Reason: Live gate not eligible  | Mode: LIVE                 │
│ Retries: 1  | Status: RETRYING (next in 20s)                │
│ [Expand] [Details] [Manual Retry] [Manual Override]         │
│                                                                │
│ ... 230 more signals                                          │
│                                                                │
│                    [Load More] [Page 2]                       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 4. Pending Signals - Real-Time Queue

### URL: `/trader/signals/pending`

### Layout
```
┌────────────────────────────────────────────────────────────────┐
│           PENDING SIGNAL EXECUTION QUEUE                       │
├────────────────────────────────────────────────────────────────┤
│  3 signals waiting  | Strategies: VWAP_BOUNCE, NSE_SPIKE     │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ 🟠 Waiting 45 sec  | BUY 50 INFY @ Market  | VWAP_BOUNCE    │
│    ├─ Current Step: Submitted to ZERODHA                    │
│    ├─ Order ID: 550e8400-e29b-41d4                         │
│    ├─ Broker Order: 220512001234                            │
│    ├─ Execution Mode: LIVE                                  │
│    └─ [Cancel] [Force Fill] [Details]                      │
│                                                                │
│ 🟠 Waiting 35 sec  | SELL 30 TCS @ Market | NSE_SPIKE       │
│    ├─ Current Step: Broker Accepted                         │
│    ├─ Order ID: 660e8400-e29b-41d4                         │
│    ├─ Broker Order: 220512001235                            │
│    ├─ Execution Mode: PAPER (fallback)                      │
│    └─ [Cancel] [Force Fill] [Details]                      │
│                                                                │
│ 🟠 Waiting 20 sec  | BUY 25 HDFC @ Market | VWAP_BOUNCE     │
│    ├─ Current Step: Order Created (validating)              │
│    ├─ Order ID: 770e8400-e29b-41d4                         │
│    ├─ Execution Mode: LIVE                                  │
│    └─ [Cancel] [Details]                                   │
│                                                                │
│                    [Refresh] [Auto-refresh: ON]              │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 5. Issues & Failures - Manual Intervention

### URL: `/trader/signals/issues`

### Layout
```
┌────────────────────────────────────────────────────────────────┐
│          SIGNAL ISSUES REQUIRING INTERVENTION                 │
├────────────────────────────────────────────────────────────────┤
│  8 failed signals (24h) | ⚠️ 2 need immediate attention     │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ 🔴 CRITICAL - Manual Action Needed                           │
│                                                                │
│   Signal: BUY 40 RELIANCE @ Market                          │
│   Strategy: ADV_CASH                                         │
│   Failure: "Live gate not eligible - broker verification"   │
│   Timestamp: 13:06:15                                        │
│   Retries: 2/3 (next in 15 seconds)                         │
│   Suggestion: Verify broker account status                   │
│   Actions: [Manual Retry] [Override to PAPER] [Cancel]      │
│                                                                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ 🟠 AUTO-RETRYING (Fallback in progress)                      │
│                                                                │
│   Signal: SELL 25 HDFC @ Market                             │
│   Strategy: VWAP_BOUNCE                                     │
│   Failure: "Risk check failed - max daily loss exceeded"    │
│   Timestamp: 13:09:42                                        │
│   Retries: 1/3 (next in 1 minute 20 seconds)                │
│   Fallback Chain: LIVE → BOTH → PAPER                       │
│   Suggestion: Check daily P&L, may execute in PAPER mode    │
│   Actions: [View Details] [Force Override] [Cancel]         │
│                                                                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ 🟢 RECOVERED (Auto-retry succeeded)                          │
│                                                                │
│   Signal: BUY 50 INFY @ Market                              │
│   Strategy: VWAP_BOUNCE                                     │
│   Original Failure: "Exposure limits exceeded"              │
│   Timestamp: 13:04:30                                        │
│   Retries: 1 (recovered on 2nd attempt)                      │
│   Recovery Mode: BOTH (instead of LIVE)                      │
│   Status: ✓ FILLED (executed via fallback)                   │
│   Actions: [View Execution] [Archive]                        │
│                                                                │
│ ... 5 more recovered signals ...                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 6. Status Color Coding

```
🟢 GREEN (SUCCESS)
   └─ FILLED, COMPLETED, ACCEPTED

🟡 YELLOW (IN PROGRESS)
   └─ GENERATED, DISPATCHED, ORDER_CREATED, SUBMITTED, RETRYING

🟠 ORANGE (DELAYED)
   └─ Waiting > 30 seconds
   └─ Retrying with fallback

🔴 RED (FAILED)
   └─ VALIDATION_FAILED, SIZING_FAILED, RISK_FAILED, REJECTED
   └─ Waiting for manual intervention or auto-retry exhausted
```

---

## 7. API Integration Points

### For React/Angular Frontend:

```typescript
// Fetch signal details
GET /api/trader/signals/{signalId}/track

// Fetch history
GET /api/trader/signals/history?page=0&size=20

// Get dashboard stats
GET /api/trader/signals/dashboard

// Get pending signals
GET /api/trader/signals/pending?strategyKey=VWAP_BOUNCE

// Get issues
GET /api/trader/signals/issues

// Real-time updates (WebSocket)
WS /api/trader/signals/stream/{signalId}
```

### Response Format:
```json
{
  "signalId": "uuid",
  "strategyKey": "VWAP_BOUNCE",
  "symbol": "INFY",
  "status": "FILLED",
  "executionMode": "LIVE",
  "broker": {
    "vendor": "ZERODHA",
    "brokerOrderId": "220512001234"
  },
  "execution": {
    "currentStep": "Trade execution completed",
    "executionTimeMs": 2850,
    "createdAt": "2026-06-04T13:15:00Z",
    "filledAt": "2026-06-04T13:15:02.85Z"
  }
}
```

---

## 8. Real-Time Updates

For live tracking, implement WebSocket connection:

```typescript
// Connect to signal stream
const ws = new WebSocket('wss://api.yourdomain.com/api/trader/signals/stream/' + signalId);

ws.onmessage = (event) => {
  const update = JSON.parse(event.data);
  // update.currentStep
  // update.status
  // update.executionTimeMs
  // update.executionProgress (0-100%)
  
  // Refresh UI
  updateSignalTracker(update);
};
```

---

## Summary

This UI provides:
✅ **Complete Visibility**: Every step of signal execution  
✅ **Real-time Status**: Live updates via WebSocket  
✅ **Broker Transparency**: Who executed, when, where  
✅ **Auto-Recovery**: See automatic fallback retries in action  
✅ **Manual Override**: Ability to force execution or cancel  
✅ **Historical Tracking**: Full execution history with metrics  
✅ **Issue Management**: Failures grouped with suggested fixes  

