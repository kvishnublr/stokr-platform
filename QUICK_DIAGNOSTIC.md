# ⚡ QUICK DIAGNOSTIC - Signal Failure Identification

**Time to identify issue: 5 minutes**

---

## 🎯 RUN THESE 3 QUERIES NOW

```bash
# Query 1: Do confidence scores exist?
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as score_count FROM confidence_scores WHERE timestamp > NOW() - INTERVAL '5 minutes';"

# Query 2: Do trader configs exist?
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as config_count FROM confidence_strategy_config WHERE enabled = true;"

# Query 3: Do signals exist?
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as signal_count FROM strategy_signals WHERE created_at > NOW() - INTERVAL '10 minutes';"
```

---

## 🔍 INTERPRET YOUR RESULTS

### Scenario 1: [0, ?, ?] - No confidence scores
```
Your numbers: 0 scores, ? configs, ? signals
Problem: STAGE 1 FAILED ❌
Root Cause: Order flow data is not being processed
Issue: OrderFlowCollectorService (Phase 1) not running or producing data

FIX:
  1. Check if Phase 1 services are running
  2. Verify order_flow_snapshots table has recent data
  3. Restart ConfidenceScoreCalculatorService
  4. Wait 60 seconds and re-run Query 1
```

### Scenario 2: [>0, 0, ?] - Scores exist but no trader configs
```
Your numbers: 100+ scores, 0 configs, ? signals
Problem: STAGE 2 FAILED ❌
Root Cause: No trader has configured a threshold

FIX:
  1. Create trader config via API:
     curl -X POST http://173.249.55.84:8080/api/confidence-strategy/config \
       -H "Content-Type: application/json" \
       -d '{"traderId":"550e8400-e29b-41d4-a716-446655440000","threshold":70}'
  
  2. Wait 2 minutes for signal generation to run
  3. Re-run Query 3
```

### Scenario 3: [>0, >0, 0] - Configs exist but NO signals generated
```
Your numbers: 100+ scores, 1+ configs, 0 signals
Problem: STAGE 3 FAILED ❌
Root Cause: Confidence scores are below threshold

FIX:
  1. Check score distribution:
     psql -c "SELECT confidence_score, COUNT(*) 
              FROM confidence_scores 
              WHERE timestamp > NOW() - INTERVAL '5 minutes'
              GROUP BY confidence_score 
              ORDER BY confidence_score DESC LIMIT 20;"
  
  2. If all scores < 70 but threshold is 70:
     → Confidence calculation is too conservative
     → Lower trader threshold to 60 (test)
     → OR improve confidence calculation
  
  3. Run signal generation manually:
     curl -X POST http://173.249.55.84:8080/api/confidence-strategy/test/generate-signals-now
  
  4. Re-run Query 3
```

### Scenario 4: [>0, >0, >0] - Signals in DB, test API
```
Your numbers: 100+ scores, 1+ configs, 50+ signals
Problem: Signals exist in database! Check API delivery.

FIX:
  1. Test API endpoint:
     curl http://173.249.55.84:8080/api/confidence-strategy/signals/above/70 | jq 'length'
  
  2. If API returns signals:
     → Issue is trader dashboard/authorization (Stage 5)
     → Verify trader_id in signals matches trader making request
  
  3. If API returns empty []:
     → Issue is API filtering (Stage 4)
     → Check API endpoint code for timestamp/user filters
```

---

## 📊 DIAGNOSTIC FLOW CHART

```
START
  ↓
Run Query 1 (confidence scores)
  ↓
  ├─ 0 results? → STOP: OrderFlowCollector not running (Stage 1)
  │
  └─ >0 results? Continue
       ↓
       Run Query 2 (trader configs)
       ↓
       ├─ 0 results? → STOP: Create trader config (Stage 2)
       │
       └─ >0 results? Continue
            ↓
            Run Query 3 (signals)
            ↓
            ├─ 0 results? → Check score distribution (Stage 3)
            │             → Lower threshold OR improve calc
            │
            └─ >0 results? Signals exist! Check API
                          ↓
                          Test API endpoint
                          ↓
                          ├─ Returns empty []? → Fix API filtering (Stage 4)
                          │
                          └─ Returns signals? → Check trader dashboard (Stage 5)
```

---

## ⚠️ COMMON ISSUES & QUICK FIXES

| Issue | Check | Fix |
|-------|-------|-----|
| Query 1 = 0 | ConfidenceScoreCalculatorService running? | Restart service, wait 60s |
| Query 2 = 0 | Did you create trader config? | POST /api/confidence-strategy/config |
| Query 3 = 0 | Are scores >= threshold? | Lower threshold to 60 (test) |
| Signals in DB but API empty | Endpoint filtering | Check API code for aggressive filters |
| API returns signals but trader doesn't see | Trader authorization | Verify trader_id in signal |

---

## 🚀 NEXT STEPS AFTER DIAGNOSIS

**Once you identify which stage is failing, tell me:**

1. The 3 query results (or "failed" if query errored)
2. The stage that's failing (1-5)
3. What you've already checked

**I will then:**

1. Provide exact root cause
2. Show you the exact code to fix
3. Create the fix implementation
4. Deploy and verify

---

## 📞 EXAMPLE DIAGNOSIS REPORT

**User reports:** "Signals generated but not showing in trader account"

**I run:**
```bash
Query 1: 100 ✅
Query 2: 1 ✅
Query 3: 450 ✅
API test: 184 signals returned ✅
Dashboard: No signals shown ❌
```

**Diagnosis:** Stage 5 Failed - Trader dashboard issue
- Signals exist in DB
- API returns them correctly
- But trader's dashboard not showing them

**Root cause:** Trader ID mismatch OR WebSocket not connected

**Fix:** Verify signal.trader_id == current_trader_id in request

---

## 🎯 WHAT I NEED FROM YOU

Run these commands and share the output:

```bash
# Test 1: Confidence scores
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as count FROM confidence_scores WHERE timestamp > NOW() - INTERVAL '5 minutes';"

# Test 2: Trader configs
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as count FROM confidence_strategy_config WHERE enabled = true;"

# Test 3: Signals generated
psql -h localhost -U postgres -d stokr_platform -c \
"SELECT COUNT(*) as count FROM strategy_signals WHERE created_at > NOW() - INTERVAL '10 minutes';"

# Test 4: API working?
curl -s http://173.249.55.84:8080/api/confidence-strategy/signals/above/70 | wc -l
```

**Share these 4 numbers and I'll tell you exactly what's broken!**

---

## 🛠️ IF ALL ELSE FAILS

Check the complete diagnostic document:
- [SIGNAL_GENERATION_DIAGNOSTICS.md](SIGNAL_GENERATION_DIAGNOSTICS.md)

It contains:
- All 12 failure points with symptoms
- 5 detailed diagnostic queries per stage
- Failure matrix
- Admin dashboard template
- Implementation guide for diagnostics endpoint
