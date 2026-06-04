# 🔍 SIGNAL GENERATION DIAGNOSTICS & FAILURE ANALYSIS

**Purpose**: Identify exactly where signals fail in the generation pipeline  
**Date**: 2026-06-04

---

## 📊 SIGNAL GENERATION PIPELINE (Complete Flow)

```
┌─────────────────────────────────────────────────────────────────────┐
│ STAGE 1: CONFIDENCE CALCULATION                                     │
├─────────────────────────────────────────────────────────────────────┤
│ Every 60 seconds:                                                   │
│                                                                      │
│ ① Order Book Data arrives                                          │
│    └─ Source: NSE live feed / OrderFlowCollectorService            │
│    └─ Metric: Real-time bid/ask/volumes                            │
│                                                                      │
│ ② ConfidenceScoreCalculatorService processes                       │
│    └─ For each of 100 Nifty 100 stocks                             │
│    └─ Calls: metricsService.getOrderFlowSignal(symbol)             │
│    └─ Output: Confidence 0-100, pressures, liquidity               │
│                                                                      │
│ ③ Store in confidence_scores table                                 │
│    └─ symbol, timestamp, confidence_score, pressures, liquidity    │
│                                                                      │
│ ⚠️  FAILURE POINT 1: Order flow data missing                        │
│    └─ Check: Is OrderFlowCollectorService running?                 │
│    └─ Check: order_flow_snapshots table populated?                 │
│    └─ Check: Any errors in Phase 1 services?                       │
│                                                                      │
│ ⚠️  FAILURE POINT 2: Confidence calculation errors                  │
│    └─ Check: metricsService returning null/error?                  │
│    └─ Check: Database insert failures?                             │
│    └─ Check: Null pointer exceptions in metrics calc?              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
              ↓ (Gap: 70 seconds)
┌─────────────────────────────────────────────────────────────────────┐
│ STAGE 2: TRADER CONFIGURATION CHECK                                 │
├─────────────────────────────────────────────────────────────────────┤
│ Every 120 seconds (after confidence calculation):                   │
│                                                                      │
│ ① ConfidenceBasedSignalGeneratorService starts                     │
│    └─ Retrieves enabled trader configs                             │
│    └─ For each trader threshold (60/70/80/90)                      │
│                                                                      │
│ ⚠️  FAILURE POINT 3: No trader configurations                       │
│    └─ Check: confidence_strategy_config table empty?               │
│    └─ Check: enabled = true?                                       │
│    └─ Check: API /config endpoint called to create configs?        │
│                                                                      │
│ ⚠️  FAILURE POINT 4: Invalid configuration                          │
│    └─ Check: traderId is valid UUID?                               │
│    └─ Check: threshold is 60/70/80/90?                             │
│    └─ Check: Database constraints violated?                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────────────────┐
│ STAGE 3: SIGNAL FILTERING & GENERATION                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│ For each trader config (e.g., threshold = 70):                      │
│                                                                      │
│ ① Query: confidence_scores WHERE confidence_score > threshold      │
│    └─ Example: confidence_score > 70                               │
│    └─ Output: List of qualifying symbols                           │
│                                                                      │
│ ⚠️  FAILURE POINT 5: No symbols matching threshold                  │
│    └─ Check: confidence_scores table has data?                     │
│    └─ Check: Are calculated scores > threshold?                    │
│    └─ Diagnosis: If 0 signals, confidence scores might be too low  │
│                                                                      │
│ ② For each qualifying symbol:                                      │
│    └─ Create signal record                                         │
│    └─ Store in strategy_signals table                              │
│    └─ Details: symbol, confidence, trader_id, threshold, etc.      │
│                                                                      │
│ ⚠️  FAILURE POINT 6: Signal insertion fails                         │
│    └─ Check: Database permissions?                                 │
│    └─ Check: Foreign key constraints?                              │
│    └─ Check: Disk space available?                                 │
│    └─ Check: Database connection pooling issues?                   │
│                                                                      │
│ ⚠️  FAILURE POINT 7: Duplicate signal suppression                   │
│    └─ Check: Is duplicate signal detection working?                │
│    └─ Check: Last signal timestamp for symbol?                     │
│    └─ Check: Time window for suppression?                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────────────────┐
│ STAGE 4: TRADER RETRIEVAL & DELIVERY                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│ Trader queries: GET /api/confidence-strategy/signals/above/{th}     │
│                                                                      │
│ ⚠️  FAILURE POINT 8: API endpoint not returning signals              │
│    └─ Check: /signals/above/{threshold} endpoint working?          │
│    └─ Check: API response codes (200 vs 500)?                      │
│    └─ Check: Filtering logic in controller?                        │
│                                                                      │
│ ⚠️  FAILURE POINT 9: Signals in database but not shown to trader    │
│    └─ Check: User/trader filtering applied?                        │
│    └─ Check: Timestamp filtering removing fresh signals?           │
│    └─ Check: Cache stale data being returned?                      │
│                                                                      │
│ ⚠️  FAILURE POINT 10: Trader account mismatch                       │
│    └─ Check: Correct trader_id in query?                           │
│    └─ Check: Signal.trader_id matches query trader?                │
│    └─ Check: Multiple trader config scenarios handled?             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────────────────┐
│ STAGE 5: DASHBOARD & NOTIFICATION                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│ ⚠️  FAILURE POINT 11: Dashboard not updating                         │
│    └─ Check: WebSocket connection active?                          │
│    └─ Check: Real-time updates configured?                         │
│    └─ Check: Browser cache issues?                                 │
│                                                                      │
│ ⚠️  FAILURE POINT 12: Notifications not sent                         │
│    └─ Check: Notification service enabled?                         │
│    └─ Check: Email/SMS/Push configured?                            │
│    └─ Check: Trader notification preferences?                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🎯 DIAGNOSTIC QUERIES & ENDPOINTS

### STAGE 1: Check Confidence Data Exists

```sql
-- How many confidence scores in last 5 minutes?
SELECT COUNT(*) as confidence_count, 
       COUNT(DISTINCT symbol) as unique_symbols,
       MIN(confidence_score) as min_score,
       MAX(confidence_score) as max_score,
       AVG(confidence_score) as avg_score
FROM confidence_scores
WHERE timestamp > NOW() - INTERVAL '5 minutes';

-- Are scores distributed across ranges?
SELECT 
  CASE 
    WHEN confidence_score >= 80 THEN '80-100 (Strong)'
    WHEN confidence_score >= 70 THEN '70-79 (Good)'
    WHEN confidence_score >= 60 THEN '60-69 (Moderate)'
    ELSE '< 60 (Weak)'
  END as confidence_bracket,
  COUNT(*) as count
FROM confidence_scores
WHERE timestamp > NOW() - INTERVAL '5 minutes'
GROUP BY confidence_bracket
ORDER BY confidence_bracket;

-- Which symbols have highest confidence?
SELECT symbol, MAX(confidence_score) as latest_confidence
FROM confidence_scores
WHERE timestamp > NOW() - INTERVAL '5 minutes'
GROUP BY symbol
ORDER BY latest_confidence DESC
LIMIT 10;
```

### STAGE 2: Check Trader Configurations

```sql
-- Do trader configs exist?
SELECT COUNT(*) as trader_count,
       COUNT(DISTINCT trader_id) as unique_traders
FROM confidence_strategy_config
WHERE enabled = true;

-- Breakdown by threshold
SELECT min_confidence_threshold,
       COUNT(*) as trader_count,
       STRING_AGG(DISTINCT trader_id::text, ', ') as trader_ids
FROM confidence_strategy_config
WHERE enabled = true
GROUP BY min_confidence_threshold;

-- Specific trader config
SELECT * FROM confidence_strategy_config
WHERE trader_id = 'your-trader-uuid'
AND enabled = true;
```

### STAGE 3: Check Signal Generation

```sql
-- How many signals generated in last 10 minutes?
SELECT COUNT(*) as total_signals,
       COUNT(DISTINCT symbol) as unique_symbols,
       COUNT(DISTINCT trader_id) as unique_traders,
       MIN(created_at) as earliest_signal,
       MAX(created_at) as latest_signal
FROM strategy_signals
WHERE created_at > NOW() - INTERVAL '10 minutes';

-- Signals by threshold (matching configs)
SELECT 
  'Threshold 60' as threshold_level,
  COUNT(*) as signal_count
FROM strategy_signals ss
WHERE created_at > NOW() - INTERVAL '10 minutes'
AND ss.confidence_score > 60
UNION ALL
SELECT 
  'Threshold 70',
  COUNT(*)
FROM strategy_signals
WHERE created_at > NOW() - INTERVAL '10 minutes'
AND confidence_score > 70
UNION ALL
SELECT 
  'Threshold 80',
  COUNT(*)
FROM strategy_signals
WHERE created_at > NOW() - INTERVAL '10 minutes'
AND confidence_score > 80
UNION ALL
SELECT 
  'Threshold 90',
  COUNT(*)
FROM strategy_signals
WHERE created_at > NOW() - INTERVAL '10 minutes'
AND confidence_score > 90;

-- Signals missing from expected thresholds
SELECT COUNT(*) as missing_signals
FROM confidence_scores cs
WHERE cs.timestamp > NOW() - INTERVAL '5 minutes'
AND cs.confidence_score > 70
AND NOT EXISTS (
  SELECT 1 FROM strategy_signals ss
  WHERE ss.symbol = cs.symbol
  AND ss.created_at > NOW() - INTERVAL '10 minutes'
  AND ss.confidence_score > 70
);
```

### STAGE 4: Check API Endpoints

```bash
# Get today's signal counts
curl -s http://173.249.55.84:8080/api/confidence-strategy/today/signal-count | jq .

# Get signals above 70
curl -s http://173.249.55.84:8080/api/confidence-strategy/signals/above/70 | jq 'length'

# Get latest scores
curl -s http://173.249.55.84:8080/api/confidence-strategy/latest-scores | jq 'length'

# Check dashboard stats
curl -s http://173.249.55.84:8080/api/confidence-strategy/dashboard/stats | jq .
```

---

## 🔴 FAILURE DIAGNOSIS MATRIX

| Failure Point | Symptom | Root Cause | Check |
|---------------|---------|-----------|-------|
| 1 | No confidence scores | Order flow data missing | Is OrderFlowCollectorService running? |
| 2 | Scores all zeros/null | Calculation error | Check service logs for exceptions |
| 3 | No signals at all | No trader configs | Is config API being called? |
| 4 | Config rejected | Invalid values | Threshold must be 60/70/80/90 |
| 5 | Signals for threshold 60 but not 70 | Scores < 70 | Check avg_score in diagnosis query |
| 6 | Signals created but won't insert | DB error | Check disk space, permissions, FK constraints |
| 7 | Duplicate signals appearing | No suppression | Add dedupe logic to generator |
| 8 | API returns 500 error | Endpoint broken | Check controller logs |
| 9 | Signals in DB but not in API | Filter too restrictive | Check timestamp/user filters |
| 10 | Wrong trader seeing signals | ID mismatch | Verify trader_id matches in DB |
| 11 | Dashboard not real-time | No WebSocket | Check frontend connection |
| 12 | No notifications sent | Service disabled | Enable notification service |

---

## 📊 GRAPHIC: SIGNAL FLOW WITH MONITORING POINTS

```
MINUTE 0:00
┌─────────────────────────────────────────────────────────────┐
│ ORDER BOOK DATA (Real-time) → OrderFlowCollectorService      │
│                                                              │
│ Monitor Point 1: Are order_flow_snapshots being updated?    │
│ Query: SELECT COUNT(*) FROM order_flow_snapshots            │
│        WHERE timestamp > NOW() - INTERVAL '1 minute';       │
│ Expected: 100+ rows per minute                              │
└──────────────┬──────────────────────────────────────────────┘
               ↓ (Immediate)
┌──────────────────────────────────────────────────────────────┐
│ CONFIDENCE CALCULATION (ConfidenceScoreCalculatorService)    │
│ Duration: 2 seconds                                          │
│                                                              │
│ Monitor Point 2: Are confidence_scores being inserted?      │
│ Query: SELECT COUNT(*) FROM confidence_scores               │
│        WHERE timestamp > NOW() - INTERVAL '2 minutes';      │
│ Expected: 100 rows per cycle                                │
└──────────────┬──────────────────────────────────────────────┘
               ↓ (Gap: 70 seconds)
┌──────────────────────────────────────────────────────────────┐
│ SIGNAL GENERATION BEGINS                                     │
│                                                              │
│ Monitor Point 3: Are trader configs present?                │
│ Query: SELECT COUNT(*) FROM confidence_strategy_config      │
│        WHERE enabled = true;                                │
│ Expected: > 0 (at least 1 trader)                           │
└──────────────┬──────────────────────────────────────────────┘
               ↓
MINUTE 0:02 (SIGNAL GENERATION SERVICE RUNS)
┌──────────────────────────────────────────────────────────────┐
│ For each trader threshold (60/70/80/90):                     │
│                                                              │
│ Monitor Point 4: How many scores > threshold?               │
│ Query: SELECT COUNT(*) FROM confidence_scores               │
│        WHERE confidence_score > 70                           │
│        AND timestamp > NOW() - INTERVAL '2 minutes';        │
│ Expected: 50-200 depending on market                        │
│                                                              │
│ Monitor Point 5: Signals actually created?                  │
│ Query: SELECT COUNT(*) FROM strategy_signals                │
│        WHERE created_at > NOW() - INTERVAL '2 minutes'      │
│        AND confidence_score > 70;                           │
│ Expected: Should match count from Point 4                   │
└──────────────┬──────────────────────────────────────────────┘
               ↓
┌──────────────────────────────────────────────────────────────┐
│ API DELIVERY                                                 │
│                                                              │
│ Monitor Point 6: API returning signals?                     │
│ curl /api/confidence-strategy/signals/above/70              │
│ Expected: Array of signals                                  │
│                                                              │
│ Monitor Point 7: Correct trader seeing them?                │
│ Verify: trader_id in signals matches query trader_id        │
└──────────────┬──────────────────────────────────────────────┘
               ↓
┌──────────────────────────────────────────────────────────────┐
│ TRADER DASHBOARD & NOTIFICATIONS                            │
│                                                              │
│ Monitor Point 8: Dashboard updated? Notifications sent?     │
│ Check: Browser shows updated signal count                   │
│ Check: Trader received alert/notification                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 🛠️ DIAGNOSTIC DASHBOARD REPORT

Create this report to identify exactly where signals fail:

```bash
#!/bin/bash

echo "═══════════════════════════════════════════════════════════"
echo "  SIGNAL GENERATION DIAGNOSTIC REPORT"
echo "═══════════════════════════════════════════════════════════"
echo ""

# STAGE 1: Confidence Data
echo "📊 STAGE 1: CONFIDENCE SCORES (Last 5 min)"
psql -h localhost -U postgres -d stokr_platform -c "
SELECT COUNT(*) as total_scores,
       COUNT(DISTINCT symbol) as unique_symbols,
       ROUND(AVG(confidence_score)::numeric, 2) as avg_confidence,
       MIN(confidence_score) as min_score,
       MAX(confidence_score) as max_score
FROM confidence_scores
WHERE timestamp > NOW() - INTERVAL '5 minutes';"

# STAGE 2: Trader Configs
echo ""
echo "👥 STAGE 2: TRADER CONFIGURATIONS"
psql -h localhost -U postgres -d stokr_platform -c "
SELECT min_confidence_threshold as threshold,
       COUNT(*) as trader_count,
       string_agg(enabled::text, ', ') as all_enabled
FROM confidence_strategy_config
GROUP BY min_confidence_threshold
ORDER BY min_confidence_threshold;"

# STAGE 3: Signal Generation
echo ""
echo "⚡ STAGE 3: SIGNALS GENERATED (Last 10 min)"
psql -h localhost -U postgres -d stokr_platform -c "
SELECT COUNT(*) as total_signals,
       COUNT(DISTINCT symbol) as unique_symbols,
       COUNT(DISTINCT trader_id) as unique_traders,
       ROUND(AVG(confidence_score)::numeric, 2) as avg_signal_confidence
FROM strategy_signals
WHERE created_at > NOW() - INTERVAL '10 minutes';"

# STAGE 4: API Response
echo ""
echo "🔌 STAGE 4: API ENDPOINT TEST"
curl -s http://173.249.55.84:8080/api/confidence-strategy/dashboard/stats | jq .

# STAGE 5: Mismatch Detection
echo ""
echo "⚠️  STAGE 5: MISSING SIGNALS (Confidence exists but signal doesn't)"
psql -h localhost -U postgres -d stokr_platform -c "
SELECT COUNT(*) as signals_missing_from_api
FROM confidence_scores cs
WHERE cs.timestamp > NOW() - INTERVAL '5 minutes'
AND cs.confidence_score > 70
AND NOT EXISTS (
  SELECT 1 FROM strategy_signals ss
  WHERE ss.symbol = cs.symbol
  AND ss.created_at > NOW() - INTERVAL '10 minutes'
  AND ss.confidence_score > 70
);"

echo ""
echo "═══════════════════════════════════════════════════════════"
```

---

## ✅ ANALYSIS APPROACH (Before Coding)

### If signals = 0:
1. Check Stage 1: Are confidence scores being calculated? (Monitor Point 1-2)
2. Check Stage 2: Do trader configs exist? (Monitor Point 3)
3. If configs exist but no signals: Are scores < threshold? (Monitor Point 4)
4. **FIX**: Either increase threshold or improve confidence calculation

### If signals in DB but not in API:
1. Check API endpoint: Are there DB records? (Monitor Point 5)
2. Check filtering: Are API filters too restrictive? (Monitor Point 6)
3. **FIX**: Adjust API query filters or timestamp handling

### If signals in API but not in trader account:
1. Check trader ID: Does signal have correct trader_id? (Monitor Point 7)
2. Check authorization: Is trader allowed to see this signal? (Monitor Point 7)
3. **FIX**: Verify trader config vs signal trader_id match

### If dashboard not updating:
1. Check WebSocket: Is real-time connection active? (Monitor Point 8)
2. Check cache: Is stale data being served? (Monitor Point 8)
3. **FIX**: Invalidate cache or refresh WebSocket

---

## 🎯 IMPLEMENTATION STRATEGY

1. **Create diagnostic endpoint**: GET /api/confidence-strategy/diagnostics
   - Returns all 8 monitoring points in one call
   - Shows: scores → configs → signals → API → delivery

2. **Add logging at each stage**:
   - ConfidenceCalculatorService: Log score count
   - SignalGeneratorService: Log query count & result count
   - Controller: Log API requests & response counts
   - Database triggers: Log insert/delete counts

3. **Build admin dashboard**:
   - Real-time pipeline visualization
   - Show bottleneck (which stage is slow/failing)
   - Drill-down to details

---

## 📋 BEFORE CODING: ANSWER THESE QUESTIONS

1. **Where do signals fail?**
   - [ ] Stage 1: Confidence scores not calculated
   - [ ] Stage 2: No trader configurations
   - [ ] Stage 3: Scores don't match thresholds
   - [ ] Stage 4: API not returning signals
   - [ ] Stage 5: Trader dashboard not showing

2. **What's the current state?**
   - [ ] Confidence scores: ? count in last 5 min
   - [ ] Trader configs: ? count
   - [ ] Signals in DB: ? count in last 10 min
   - [ ] API response: ? status code

3. **What's expected vs actual?**
   - Expected: 100 confidence scores/min → 200-600 signals/2min
   - Actual: ?

---

Run the **diagnostic dashboard report** first to identify exactly where the issue is.
