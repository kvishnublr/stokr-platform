# Chartink Signal Generation Validation Report
**Date:** 2026-06-18 11:45 AM IST  
**Server:** 173.249.55.84  
**Container:** stokr-api (stokr-platform-api)

---

## 📋 DEPLOYED VERSION

**Image:** `stokr-platform-api`  
**Built:** 2026-06-17T09:18:48Z (June 17, 2026 at 2:48 PM IST)  
**Branch:** Release_v6  
**Latest Commit:** `5253929e` - "feat: strategy-based webhook evaluation with ChartinkTickBuffer"

### Recent Commits (Last 2 Days):
1. ✅ Strategy-based webhook evaluation with ChartinkTickBuffer
2. ✅ Add STOKR_ prefix support in StrategyRouter
3. ✅ V10 migration to seed 5 Chartink strategies
4. ✅ Per-trader config, qty calc, live mode
5. ✅ 5 NSE intraday strategies for ₹15K deployment

**Architecture:** This is the **stokr-lite monolith** with Chartink webhook integration

---

## 🔍 CHARTINK INTEGRATION STATUS

### ✅ What's Configured:
1. **5 Strategies Enabled:**
   - VWAP Triple Confirmation (ID: 1)
   - ORB-V Breakout (ID: 3)
   - Morning Surge Reversal (ID: 4)
   - Pre-Open Trade Book (ID: 5)
   - Trade Book Imbalance (ID: 2)

2. **Webhook Endpoints Available:**
   - `POST /webhooks/chartink/preopen` - Pre-market signals
   - `POST /webhooks/chartink/intraday` - Intraday scanner hits
   - `POST /webhooks/chartink/exit` - Exit triggers

3. **3 Active Deployments:**
   - User 3: LIVE mode, Strategy 1 (VWAP), ₹10K capital
   - User 4: PAPER mode, Strategy 1 (VWAP), ₹15K capital (2 deployments)

4. **Trader Configs:** All 4 users configured with PAPER/LIVE modes

### ❌ What's NOT Working:

#### **CRITICAL ISSUE: NO CHARTINK WEBHOOKS BEING RECEIVED**

**Evidence:**
- ❌ ZERO logs showing "Chartink preopen webhook" or "Chartink intraday webhook"
- ❌ `chartink_signals` table doesn't exist (never created)
- ❌ No webhook hits in the last 24 hours
- ❌ Chartink Premium NOT configured or NOT sending webhooks to server

#### Signal Generation Status:
```
Total signals in database: 14
Signals today (June 18): 3 (all at 3-4 AM, outside market hours!)
Signals during market hours (9:15 AM - 3:30 PM): 0 ❌
```

**Signal Status Breakdown:**
- ENSEMBLE_FILTERED: 5 signals (filtered but not executed)
- GENERATED: 5 signals (generated but not executed)
- **EXECUTED: 0 signals** ❌

#### Confidence-Based Signal Generator:
The logs show it's running every 2 minutes but generating ZERO signals:
```
11:39:19 - Signal generation complete. Total: 0
11:41:20 - Signal generation complete. Total: 0
11:43:19 - Signal generation complete. Total: 0
11:45:23 - Signal generation complete. Total: 0
```

---

## 🎯 ROOT CAUSES IDENTIFIED

### 1. **Chartink Webhooks Not Configured**
**Problem:** Chartink Premium service is either:
- Not subscribed/activated
- Not configured with webhook URLs pointing to `http://173.249.55.84:8080/webhooks/chartink/*`
- Scanners not created or not running

**Impact:** NO signals can be generated because the system relies on Chartink webhooks as the primary signal source

### 2. **Confidence Score Tables Missing**
**Problem:** `confidence_scores` table doesn't exist
**Impact:** The confidence-based signal generator has no data to work with

### 3. **Signal Execution Pipeline Broken**
**Problem:** 10 signals stuck in GENERATED/ENSEMBLE_FILTERED status
**Impact:** Even if signals were generated, they're not being executed

### 4. **Market Data Source Unknown**
**Problem:** No evidence of market data feed (NSE prices) being received
**Impact:** Cannot evaluate strategies without live price data

---

## 📊 CURRENT SIGNALS IN DATABASE

| ID | Symbol   | Side | Status            | Reason                  | Created At          |
|----|----------|------|-------------------|-------------------------|---------------------|
| 14 | RELIANCE | BUY  | ENSEMBLE_FILTERED | STOKR_VWAP_TRIPLE_LONG  | 2026-06-18 03:41:29 |
| 13 | TCS      | BUY  | ENSEMBLE_FILTERED | STOKR_ORB_V_BREAKOUT    | 2026-06-18 03:32:11 |
| 12 | RELIANCE | BUY  | ENSEMBLE_FILTERED | STOKR_VWAP_TRIPLE_LONG  | 2026-06-18 03:11:57 |
| 11 | TCS      | SELL | GENERATED         | PRE_OPEN_BUY            | 2026-06-17 18:18:25 |
| 9  | INFY     | BUY  | ENSEMBLE_FILTERED | VWAP_TRIPLE_LONG        | 2026-06-17 18:15:03 |

**Note:** All signals have `null` in critical fields and were generated outside market hours

---

## ✅ WHAT NEEDS TO BE FIXED

### **IMMEDIATE (To Get Signals Working Today):**

1. **Configure Chartink Premium Webhooks:**
   - Login to Chartink Premium
   - Configure webhook URLs:
     - Preopen: `http://173.249.55.84:8080/webhooks/chartink/preopen`
     - Intraday: `http://173.249.55.84:8080/webhooks/chartink/intraday`
     - Exit: `http://173.249.55.84:8080/webhooks/chartink/exit`
   - Ensure scanners are active and running

2. **Verify Market Data Feed:**
   - Check if NSE price data is being received
   - Verify marketDataService is connected to data source

3. **Test Webhook Endpoint:**
   ```bash
   curl -X POST http://173.249.55.84:8080/webhooks/chartink/intraday \
     -H "Content-Type: application/json" \
     -d '{
       "scannerName": "STOKR_VWAP_TRIPLE_LONG",
       "symbol": "RELIANCE",
       "ltp": 2450.50,
       "side": "BUY",
       "timestamp": "2026-06-18T11:50:00"
     }'
   ```

### **MEDIUM PRIORITY:**

4. **Fix Signal Execution Pipeline:**
   - Investigate why GENERATED signals are not being executed
   - Check EntryManager and execution service logs

5. **Create Missing Tables:**
   - Run Flyway migrations for confidence_scores table
   - Ensure all Chartink-related tables exist

6. **Clean Up Duplicate Deployments:**
   - User 4 has 2 PAPER deployments for same strategy
   - Remove duplicate or consolidate

---

## 🚨 VERDICT

**Chartink Integration Status: ❌ NOT WORKING**

- ✅ Code is deployed and ready to receive webhooks
- ✅ Strategies are configured and enabled
- ✅ Database schema is mostly ready
- ❌ **Chartink Premium NOT sending webhooks** (root cause)
- ❌ **NO signals being generated during market hours**
- ❌ **Market data feed status unknown**

**Expected Behavior:** Chartink scanners detect patterns → Send webhooks → Backend evaluates → Creates signals → Executes trades

**Actual Behavior:** No webhooks received → No signals generated → No trades executed

---

## 📞 NEXT STEPS

1. Check Chartink Premium subscription status
2. Configure webhook URLs in Chartink dashboard
3. Activate/enable Chartink scanners
4. Test webhook endpoint manually
5. Monitor logs for webhook receipt
6. Verify signal generation during next market session
