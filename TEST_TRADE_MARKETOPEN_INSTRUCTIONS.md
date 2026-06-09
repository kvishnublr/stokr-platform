# 🎯 TEST_TRADE_MARKETOPEN - MARKET OPEN TEST

**Scheduled Time**: 09:15 UTC (±5 seconds from market open)  
**Purpose**: Verify signal generation and execution working correctly after deployment  
**Expected Duration**: 2-5 minutes (entry to exit)

---

# TEST SPECIFICATION

## Signal Details

| Property | Value |
|----------|-------|
| **Test Name** | TEST_TRADE_MARKETOPEN |
| **Symbol** | SBIN (SBI Bank Limited) |
| **Reason for SBIN** | Cheapest NIFTY50 stock (~600 INR/share = lowest capital required) |
| **Strategy** | INDEX_HUNT |
| **Test Mode** | YES (test_trade=true flag) |
| **Quantity** | 1 share |
| **Side** | BUY/LONG |
| **Time** | 09:15 UTC (market open) |

## Success Criteria

```
✅ Signal generated at 09:15 UTC
├─ Quality score > 75 (new quality floor)
├─ Status: PENDING → RUNNING → CLOSED
│
✅ Entry executed within 5 seconds
├─ Entry price recorded
├─ Position created in broker account
├─ Order state: FILLED
│
✅ Exit triggered within 2-5 minutes
├─ Exit price recorded
├─ Position closed in broker account
├─ Order state: FILLED
│
✅ Database outcome recorded
├─ realized_pnl: small value (~0% ±2%)
├─ outcome_comment: no manual exit reference
├─ test_trade: true
│
✅ No errors in logs
├─ No CLUSTER_DETECTION rejection (only 1 trade)
├─ No duplicate exit orders
└─ No signal lifecycle errors
```

---

# PRE-TEST CHECKLIST (Before 09:10 UTC)

Run these checks **5 minutes before market open**:

```bash
# 1. Health check
curl -s http://localhost:8080/actuator/health | jq '.status'
# Expected: "UP"

# 2. Configuration verify
curl -s http://localhost:8080/actuator/configprops | grep -i "cluster-detection-enabled"
# Expected: true

# 3. Broker connection
curl -s http://localhost:8080/api/broker/status | jq '.connected'
# Expected: true

# 4. Database connectivity
curl -s http://localhost:8080/actuator/health/db | jq '.status'
# Expected: "UP"

# 5. Clear test signals from previous days (optional)
# DELETE FROM strategy_signals WHERE test_trade=true AND DATE(created_at) < '2026-06-09'
```

If any check fails, **DO NOT PROCEED** with test trade. Fix the issue first.

---

# EXECUTION AT 09:15 UTC

## Step 1: Monitor Signal Generation (09:15)

**Expected**: System generates signal for SBIN around market open

**Monitor Command**:
```bash
# Watch logs in real-time
docker logs -f stokr-api | grep -E "SBIN|signal.generated|TEST_TRADE"

# Or check database
SELECT id, symbol, quality_score, outcome_status, created_at
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
ORDER BY created_at DESC
LIMIT 1;
```

**Expected Log Output**:
```
2026-06-09 09:15:XX signal.generated symbol=SBIN strategy=INDEX_HUNT quality=79 status=PENDING
2026-06-09 09:15:XY order.entry.submit symbol=SBIN quantity=1 side=BUY
2026-06-09 09:15:XZ order.entry.filled symbol=SBIN entry_price=640.50
2026-06-09 09:15:XA signal.status.updated symbol=SBIN status=RUNNING entry_price=640.50
```

## Step 2: Verify Entry (09:16)

**Check Position Created**:
```bash
# Zerodha (manual check)
├─ Holdings: SBIN quantity=1
├─ Entry price: ~640 (market open price)
├─ Current P&L: ±0% (small slippage expected)

# Or API query
SELECT symbol, entry_price, entry_time
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
LIMIT 1;
```

**Expected**:
- SBIN position showing in holdings
- Quantity = 1
- Entry price close to market open (SBIN usually opens around 630-650)
- Signal status = RUNNING

## Step 3: Monitor Exit (09:17-09:20)

**Expected**: System automatically exits within 2-5 minutes (exit rules trigger)

**Monitor Command**:
```bash
# Watch for exit
docker logs -f stokr-api | grep -E "SBIN.*exit|signal.closed|order.exit"

# Or check database for outcome
SELECT id, symbol, entry_price, exit_price, realized_pnl, outcome_status, outcome_comment
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
LIMIT 1;
```

**Expected Log Output**:
```
2026-06-09 09:18:XX exit.trigger symbol=SBIN reason=TARGET_HIT_OR_STOPLOSS
2026-06-09 09:18:XY order.exit.submit symbol=SBIN quantity=1 side=SELL exit_price=641.00
2026-06-09 09:18:XZ order.exit.filled symbol=SBIN exit_price=641.00
2026-06-09 09:18:XA signal.closed symbol=SBIN outcome=CLOSED realized_pnl=+10.00 pnl_pct=+0.16%
```

## Step 4: Final Verification (09:25)

After test trade completes, run this verification:

```sql
-- Check test trade completed successfully
SELECT 
    id as signal_id,
    symbol,
    created_at,
    entry_price,
    exit_price,
    realized_pnl,
    (CAST(realized_pnl AS FLOAT) / CAST(entry_price AS FLOAT)) * 100 as pnl_pct,
    outcome_status,
    outcome_comment,
    test_trade
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
ORDER BY created_at DESC
LIMIT 1;

-- Expected output:
-- signal_id: [some UUID]
-- symbol: SBIN
-- created_at: 2026-06-09 09:15:XX
-- entry_price: ~640.50
-- exit_price: ~641.50
-- realized_pnl: ~10 (or similar small value)
-- pnl_pct: ~0.16% (small profit from quick in-out)
-- outcome_status: CLOSED
-- outcome_comment: (should NOT mention "MANUAL_BROKER_EXIT")
-- test_trade: true
```

---

# PASS/FAIL CRITERIA

## ✅ PASS - If ALL of the following are true:

1. ✅ Signal created at 09:15 UTC
2. ✅ Quality score >= 75
3. ✅ Entry price recorded within 5 seconds
4. ✅ Position created in Zerodha
5. ✅ Exit triggered automatically within 5 minutes
6. ✅ Exit price recorded
7. ✅ Signal outcome = CLOSED
8. ✅ realized_pnl is a small number (±0.5%)
9. ✅ outcome_comment does NOT mention manual exit
10. ✅ test_trade = true
11. ✅ No ERROR logs during process
12. ✅ No CLUSTER_DETECTION rejection messages

## ❌ FAIL - If ANY of these occur:

| Failure Scenario | Investigation |
|------------------|----------------|
| No signal generated by 09:20 | Check strategy enabled, market hours active, quality gates |
| Quality score < 75 | Check INDEX_HUNT quality floor setting, market conditions |
| Entry not executed by 09:20 | Check broker connection, order placement, risk checks |
| Position not showing in Zerodha | Check broker sync, reconciliation, order state |
| No automatic exit by 09:25 | Check exit rules, target/stoploss prices |
| Exit price not recorded | Check exit order execution, reconciliation |
| Signal outcome NOT CLOSED | Check signal lifecycle, manual exit detection |
| realized_pnl > ±1% | Check for slippage, entry/exit timing issues |
| outcome_comment mentions "MANUAL" | Check manual exit detection firing incorrectly |
| ERROR or EXCEPTION logs | Check logs for specific error, investigate root cause |
| CLUSTER_DETECTION rejection | Bug - should not reject on single entry |
| test_trade = false | Check test mode flag setting |

---

# WHAT TO DO IF TEST FAILS

### Scenario 1: No Signal Generated

```
Investigation:
1. Check INDEX_HUNT is enabled
   └─ SELECT * FROM strategy_instances WHERE strategy_name='INDEX_HUNT'

2. Check market hours
   └─ 09:15 UTC should be within NSE trading hours

3. Check SBIN passes quality gates
   └─ SELECT symbol, trade_quality, quality_score FROM candidate_symbols

4. Check for ERROR logs
   └─ docker logs stokr-api | grep ERROR

Action:
├─ If market hours wrong: Wait for correct time
├─ If quality gates wrong: Check quality floor value (should be 75)
└─ If ERROR log: Fix issue and restart container
```

### Scenario 2: Signal Generated But Entry Not Executed

```
Investigation:
1. Check signal status
   └─ SELECT outcome_status FROM strategy_signals WHERE symbol='SBIN'
   └─ Should be RUNNING or CLOSED, not PENDING

2. Check for OrderCooldownRule rejection
   └─ docker logs stokr-api | grep -i "cooldown"
   └─ Should see nothing (only 1 trade)

3. Check broker order status
   └─ SELECT state FROM oms_orders WHERE symbol='SBIN'
   └─ Should be FILLED

4. Check risk rule rejections
   └─ docker logs stokr-api | grep "RiskDecision.reject"

Action:
├─ If PENDING: Order not filled, check broker logs
├─ If risk rejected: Check which rule, verify thresholds
└─ If state not FILLED: Check broker connection
```

### Scenario 3: Entry Executed But No Exit

```
Investigation:
1. Check exit rules configured
   └─ SELECT * FROM signal_exit_rules

2. Check if exit trigger should have fired
   └─ Current price vs target/stoploss

3. Check for manual exit detection
   └─ docker logs stokr-api | grep "broker.truth.external_exit"

4. Check exit order submission
   └─ SELECT COUNT(*) FROM oms_orders WHERE symbol='SBIN' AND side='SELL'
   └─ Should be >= 1

Action:
├─ If no exit rules: Configure exit parameters
├─ If price didn't hit trigger: Wait (market may be slow)
├─ If manual exit fired: Check why (shouldn't happen in test)
└─ If order submitted but not filled: Check broker connection
```

### Scenario 4: Test Trade Results Wrong PnL

```
Investigation:
1. Check entry vs exit prices
   └─ SELECT entry_price, exit_price, realized_pnl FROM strategy_signals WHERE symbol='SBIN'

2. Verify prices from Zerodha
   └─ Check order history for actual executed prices

3. Check for slippage
   └─ Compare order price vs actual fill price

Action:
├─ If slippage high: This is normal at market open
├─ If PnL calculation wrong: Check for calculation errors
└─ If realized_pnl > ±1%: Investigate pricing issues
```

---

# ROLLBACK IF CRITICAL FAILURE

If test reveals critical production issue:

```bash
# 1. Stop current container
docker stop stokr-api

# 2. Revert to previous working commit
git checkout 01baad21

# 3. Rebuild image
docker build -t stokr-api:backup .

# 4. Restart with backup
docker-compose up -d

# 5. Verify
curl http://localhost:8080/actuator/health
```

**Estimated restoration time**: 5-10 minutes

---

# LOGGING & EVIDENCE

**Collect evidence of test results**:

```bash
# 1. Export test signal details
SELECT *
FROM strategy_signals
WHERE symbol = 'SBIN'
AND test_trade = true
AND DATE(created_at) = '2026-06-09'
LIMIT 1;

# 2. Export all orders for test trade
SELECT *
FROM oms_orders
WHERE symbol = 'SBIN'
AND created_at >= '2026-06-09 09:15:00'
AND created_at <= '2026-06-09 09:30:00'
ORDER BY created_at;

# 3. Export logs
docker logs stokr-api > /tmp/stokr-logs-09-15.txt
grep "SBIN" /tmp/stokr-logs-09-15.txt > /tmp/sbin-test-logs.txt

# 4. Screenshot Zerodha
# Take screenshot of Holdings showing SBIN position (if still open at test time)
```

---

# SUCCESS SUMMARY

When test completes successfully:

```
✅ TEST_TRADE_MARKETOPEN PASSED

Signal: [signal_id]
Symbol: SBIN
Entry: 09:15:XX @ 640.50 INR
Exit:  09:18:XX @ 641.50 INR
P&L:   +1 point (+0.16%)
Duration: 3 minutes

Status: CLOSED
Test Mode: YES
Quality: 79 (PASS)

No errors found.
System ready for regular trading.
```

---

# AFTER TEST TRADE

Once test completes successfully:

```
1. ✅ Document results
   └─ Save signal ID, entry/exit prices, P&L

2. ✅ Check no lingering issues
   └─ docker logs stokr-api | tail -100
   └─ Verify no ERROR messages

3. ✅ Clear test signals (optional)
   └─ DELETE FROM strategy_signals WHERE test_trade=true AND symbol='SBIN'

4. ✅ Resume normal trading
   └─ System ready for rest of day
   └─ Monitor for cluster detections, cooldown enforcement
```

---

**Status**: Ready for TEST_TRADE_MARKETOPEN at 09:15 UTC

