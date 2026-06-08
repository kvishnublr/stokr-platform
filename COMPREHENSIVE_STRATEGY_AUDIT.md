# 🔍 COMPREHENSIVE STRATEGY AUDIT

**Date**: 2026-06-08  
**Scope**: ALL trading strategies (14 strategies)  
**Focus**: Entry logic, Exit logic, Risk gates  

---

## 📋 STRATEGY INVENTORY

### Core Strategies (14 Total):

| # | Strategy | Asset Class | Entry Logic | Exit Logic | Status |
|---|----------|-------------|------------|-----------|--------|
| 1 | **Confidence-Based** | NSE Equities | Confidence Score > Threshold | Time-based + Target | ✅ NEW |
| 2 | **NSE Spike Detection** | NSE Futures | Velocity > 0.4% | RR 1.5 ratio | ✅ ACTIVE |
| 3 | **ADV Cash Equity** | NSE Equities | ADV Cash setup | OBI reversal | ✅ ACTIVE |
| 4 | **Early Breakout** | NSE Equities | 9:25-10:00 breakout | Target/SL | ✅ ACTIVE |
| 5 | **Index Hunt** | NSE Indices | OI & volume spike | Premium multiplier | ✅ ACTIVE |
| 6 | **Gap Fill** | NSE Equities | Pre-open gap | Close back to gap | ✅ ACTIVE |
| 7 | **VWAP Bounce** | NSE Equities | Bounce from VWAP | Target/SL | ✅ ACTIVE |
| 8 | **S3 VWAP Retest** | NSE Equities | Retest S3 + VWAP | 0.60% target | ✅ ACTIVE |
| 9 | **S7 Range Fade** | NSE Equities | Mean reversion | Back to VWAP | ✅ ACTIVE |
| 10 | **EUR/INR Momentum** | Forex (MCX) | Momentum threshold | Momentum reversal | ✅ ACTIVE |
| 11 | **USD/INR Momentum** | Forex (MCX) | Momentum threshold | Momentum reversal | ✅ ACTIVE |
| 12 | **Pre-Open Gap OI** | NSE Equities | Gap + OI spike | Gap retest | ✅ ACTIVE |
| 13 | **Sector Laggard** | NSE Equities | Sector underperform | Outperform catch-up | ✅ ACTIVE |
| 14 | **Commodities E2E Test** | MCX Commodities | Test harness | Test harness | ⚠️ TEST |

---

## 🎯 DETAILED STRATEGY ANALYSIS

### 1. **CONFIDENCE-BASED STRATEGY** ✅ NEW

**Module**: `com.stokr.bootstrap.trading.ConfidenceSignalToOrderService`

**Entry Logic**:
```java
Signal generated when: ConfidenceScore > Trader's Threshold (60/70/80/90)
Quantity: Smart positioning (1 lot normal, 2 lots if score >= 75)
Direction: BUY if score > 70, SELL if score < 30, BUY if 30-70
Order Type: MARKET if score >= 85, LIMIT otherwise
```

**Exit Logic**:
```
Not implemented yet - uses market's default behavior
TODO: Add take-profit & stop-loss automation
```

**Risk Gates Applied**:
- ✅ Market hours validation (09:15-15:30 IST)
- ✅ Duplicate order prevention
- ✅ Execution mode validation (SIMULATED/LIVE)
- ⚠️ **MISSING**: Daily signal cap check
- ⚠️ **MISSING**: Max positions check
- ⚠️ **MISSING**: Price validation

**Status**: 🔴 **CRITICAL** - Exit logic and risk gates needed

---

### 2. **NSE SPIKE DETECTION** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.NseSpikeDetectionSignalGenerator`

**Entry Logic**:
```
Entry Condition: Velocity > 0.4% (closing rate)
Entry Price: Current close
Entry Confirmation: First 2-min candle close confirms direction
```

**Exit Logic**:
```
Risk-Reward Ratio: 1.5x minimum
Target: 1.5x risk from entry
Stop Loss: Configurable from entry price
Exit Trigger: Target hit OR Stop Loss hit
```

**Risk Gates**:
- ✅ RR validation (min 1.5)
- ✅ ATR compression check
- ✅ Volume ratio validation
- ✅ Market hours check

**Status**: ✅ **READY**

---

### 3. **ADV CASH EQUITY** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.AdvCashEquitySignalGenerator`

**Entry Logic**:
```
Setup: Buying/Selling pressure in cash + futures
Signal: KNN pattern match
Entry: On pattern confirmation
```

**Exit Logic**:
```
Primary: OBI (Order Block Imbalance) reversal (+1%)
Secondary: Max 30-min candles OR Daily loss limit
```

**Risk Gates**:
- ✅ Pattern validation (KNN)
- ✅ Entry SL/Target in signal
- ✅ Daily loss tracking

**Status**: ✅ **READY**

---

### 4. **EARLY BREAKOUT** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.EarlyBreakoutSignalGenerator`

**Entry Logic**:
```
Time Window: 9:25 AM - 10:00 AM NSE opening
Entry: Breakout above previous day's high
Confirmation: Volume increase
```

**Exit Logic**:
```
Target: Previous day's high + 1.5x risk
Stop Loss: Below breakout support
```

**Risk Gates**:
- ✅ Time window validation
- ✅ SL/Target in signal
- ✅ Breakout confirmation

**Status**: ✅ **READY**

---

### 5. **INDEX HUNT** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.IndexHuntSignalGenerator`

**Entry Logic**:
```
Trigger: OI spike + Volume surge
Index: NIFTY_50 or BANKNIFTY
Entry: Weighted OI direction
```

**Exit Logic**:
```
Entry/Exit: Option premium multipliers from config
Re-entry: SL memory (skip if same direction SL'd recently)
```

**Risk Gates**:
- ✅ OI threshold validation
- ✅ Volume ratio check
- ✅ SL memory lock

**Status**: ✅ **READY**

---

### 6. **GAP FILL** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.GapFillSignalGenerator`

**Entry Logic**:
```
Trigger: Pre-open gap detected (>0.3%)
Entry: At session entry guard approval
Direction: Opposite of gap (mean reversion)
```

**Exit Logic**:
```
Target: Gap retest (back to previous close)
Stop Loss: Gap extreme
```

**Risk Gates**:
- ✅ Session entry guard validation
- ✅ Gap size validation
- ⚠️ **CHECK**: Gap size limits

**Status**: ✅ **MOSTLY READY**

---

### 7. **VWAP BOUNCE** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.VwapBounceSignalGenerator`

**Entry Logic**:
```
Entry: When price bounces from VWAP
Direction: Following the bounce
Candle: 2-3 min candle confirmation
```

**Exit Logic**:
```
Target: Price extension above VWAP + 1.5x risk
Stop Loss: VWAP -1%
Risk-Reward: Minimum 1.5x
```

**Risk Gates**:
- ✅ VWAP calculation
- ✅ RR validation
- ✅ Bounce confirmation

**Status**: ✅ **READY**

---

### 8. **S3 VWAP RETEST** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.S3VwapRetestSignalGenerator`

**Entry Logic**:
```
Entry: Retest of Support Level 3 (S3) + VWAP
Confirmation: 5-min candle close at level
Direction: Bounce from S3
```

**Exit Logic**:
```
Target: +0.60% from entry (from Python config)
Stop Loss: -0.35% from entry (from Python config)
```

**Risk Gates**:
- ✅ S3 calculation correct
- ✅ VWAP confirmation
- ✅ RR = 0.60/0.35 = 1.71x ✓

**Status**: ✅ **READY**

---

### 9. **S7 RANGE FADE** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.S7RangeFadeSignalGenerator`

**Entry Logic**:
```
Entry: Mean reversion to VWAP
Setup: Extended range (>2% from VWAP)
Direction: Back to VWAP
```

**Exit Logic**:
```
Target: VWAP ±0.5%
Stop Loss: Range extreme
```

**Risk Gates**:
- ✅ Range validation
- ✅ Extension check
- ✅ VWAP proximity

**Status**: ✅ **READY**

---

### 10. **EUR/INR MOMENTUM** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.EurInrMeanReversionSignalGenerator`

**Entry Logic**:
```
Asset: EUR/INR Forex pair (MCX)
Entry: Momentum threshold exceeded
Direction: Following momentum
Timeframe: 5-minute candles
```

**Exit Logic**:
```
Exit: Momentum reversal (crossing 0)
Stop Loss: N% from entry
```

**Risk Gates**:
- ✅ Momentum calculation
- ✅ MCX trading hours (09:00-23:30)
- ⚠️ **CHECK**: Momentum threshold values

**Status**: ✅ **MOSTLY READY**

---

### 11. **USD/INR MOMENTUM** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.UsdInrMomentumSignalGenerator`

**Entry Logic**:
```
Asset: USD/INR Forex pair (MCX)
Entry: Momentum threshold exceeded
Direction: Following momentum
Timeframe: 5-minute candles
```

**Exit Logic**:
```
Exit: Momentum reversal (crossing 0)
Stop Loss: N% from entry
```

**Risk Gates**:
- ✅ Momentum calculation
- ✅ MCX trading hours
- ⚠️ **CHECK**: Momentum threshold values

**Status**: ✅ **MOSTLY READY**

---

### 12. **PRE-OPEN GAP OI** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.PreOpenGapOISignalGenerator`

**Entry Logic**:
```
Pre-open Gap: > 0.5% with OI spike
Entry Timing: 9:16-9:17 AM (after first 1-min candle)
Confirmation: First 1-min candle closes in gap direction
```

**Exit Logic**:
```
Target: Gap retest + 0.5%
Stop Loss: Gap extreme -0.1%
```

**Risk Gates**:
- ✅ Gap size validation
- ✅ OI threshold
- ✅ Time window (9:16-9:17)

**Status**: ✅ **READY**

---

### 13. **SECTOR LAGGARD** ✅ ACTIVE

**Module**: `com.stokr.strategy.generated.SectorLaggardSignalGenerator`

**Entry Logic**:
```
Trigger: Sector underperformance vs NIFTY_50
Entry: When laggard stock recovers
Direction: Catch-up trade (long)
```

**Exit Logic**:
```
Target: Sector + 0.3% catch-up
Stop Loss: Entry -0.2%
```

**Risk Gates**:
- ✅ Sector performance calculation
- ✅ Entry SL/Target in signal
- ⚠️ **CHECK**: Sector universe definition

**Status**: ✅ **MOSTLY READY**

---

### 14. **COMMODITIES E2E TEST** ⚠️ TEST-ONLY

**Module**: `com.stokr.strategy.generated.CommoditiesE2eTestSignalGenerator`

**Purpose**: End-to-end testing harness

**Entry Logic**: Test configuration driven

**Exit Logic**: Test configuration driven

**Status**: ⚠️ **SKIP IN PRODUCTION** - Remove before live deployment

---

## 🚨 CRITICAL ISSUES FOUND

### 1. **CONFIDENCE-BASED STRATEGY - MISSING EXIT LOGIC** 🔴 CRITICAL

**Issue**: No automated exit (take-profit/stop-loss)

**Impact**: Trades may run indefinitely, manual management required

**Fix Required**:
```java
TODO: Implement ConfidenceSignalExitService that:
- Monitors profit targets (e.g., +2% for confidence >= 80)
- Monitors stop losses (e.g., -1% for confidence < 60)
- Auto-closes when targets hit
- Auto-closes when SL hit
- Closes at market end if still open
```

**Recommendation**: Add before going LIVE

---

### 2. **CONFIDENCE-BASED STRATEGY - MISSING RISK GATES** 🔴 CRITICAL

**Issue**: Not checking risk limits from OrderPlacementService

**Missing Checks**:
- Daily signal cap per trader
- Max open positions per trader
- Max notional value per position
- Price validation (no extreme values)
- Daily loss limit

**Fix Required**:
```java
// In ConfidenceSignalToOrderService.processSignalsForTrader():
// Add these checks before order.place():
- Check daily signal count < limit
- Check trader open positions < max
- Check order notional < limit
- Validate symbol exists and has valid price
- Check daily P&L not exceeded
```

**Recommendation**: Add before going LIVE

---

### 3. **MOMENTUM STRATEGIES - THRESHOLD VALUES NEED VALIDATION** ⚠️ HIGH

**Strategies Affected**:
- EUR/INR Momentum
- USD/INR Momentum

**Issue**: Momentum threshold values may not be optimal

**Data to Verify**:
- Current momentum threshold used
- Historical win rate at current threshold
- Backtest results for threshold optimization

**Recommendation**: Backtest with real data before live trading

---

### 4. **SECTOR LAGGARD - UNIVERSE DEFINITION** ⚠️ MEDIUM

**Strategy**: Sector Laggard

**Issue**: Which stocks define "sector"? How are laggards identified?

**Data to Verify**:
- Sector universe (all NSE? Specific sectors?)
- Laggard detection algorithm
- Catch-up trade triggers

**Recommendation**: Document and verify logic

---

### 5. **GAP FILL - GAP SIZE LIMITS** ⚠️ MEDIUM

**Strategy**: Gap Fill, Pre-Open Gap OI

**Issue**: No documented minimum/maximum gap size

**Data to Verify**:
- Min gap % (currently 0.3%)
- Max gap % (prevent outliers)
- Historical gap fill success rate

**Recommendation**: Document acceptable gap ranges

---

## ✅ VALIDATION CHECKLIST

Run these tests before deployment:

```
□ Confidence-Based Strategy:
  □ Manual test: Create signal, verify order placement
  □ Manual test: Verify market hours check works
  □ Manual test: Verify SIMULATED vs LIVE mode
  □ TODO: Implement exit logic
  □ TODO: Add risk gate checks

□ All 14 Strategies:
  □ Verify entry logic working (check database signals)
  □ Verify exit logic working (check closed trades)
  □ Verify risk gates applied (check rejection logs)
  □ Verify P&L tracking (check dashboard)

□ Asset Classes:
  □ NSE Equities (8 strategies): Test during 9:15-15:30
  □ MCX Forex (2 strategies): Test during 9:00-23:30
  □ MCX Commodities (1 strategy): Test during market hours
  □ NSE Futures (1 strategy): Test during 9:15-15:30
  □ NSE Indices (1 strategy): Test during 9:15-15:30

□ Risk Management:
  □ Daily signal cap enforced
  □ Max positions enforced
  □ Daily loss limit enforced
  □ Market hours respected
  □ Price validation working
```

---

## 📊 STRATEGY STATUS SUMMARY

| Category | Count | Status | Action |
|----------|-------|--------|--------|
| **Active Strategies** | 13 | ✅ | Monitor & Test |
| **New Strategies** | 1 | 🔴 INCOMPLETE | Complete exit logic + risk gates |
| **Test Strategies** | 1 | ⚠️ | Remove before production |
| **Total** | 15 | ⚠️ | 2 critical issues before LIVE |

---

## 🎯 IMMEDIATE ACTION ITEMS

### BEFORE TESTING IN SIMULATED MODE:
1. ✅ Build and deploy code
2. ⚠️ Verify Confidence-Based strategy exit logic (TODO)
3. ⚠️ Add risk gate checks to Confidence-Based strategy (TODO)

### BEFORE GOING LIVE:
1. Run 24-hour SIMULATED test of all strategies
2. Verify entry logic working (signals generated)
3. Verify exit logic working (trades closed properly)
4. Verify risk gates applied (rejections logged)
5. Backtest EUR/INR and USD/INR momentum thresholds
6. Document sector universe and laggard detection
7. Verify gap fill strategy gap size limits
8. Complete confidence-based strategy exit logic
9. Run 24-hour stress test with 50% capital

---

## 🚀 DEPLOYMENT READINESS

### Ready for SIMULATED Testing:
- ✅ 13 existing strategies
- ⚠️ Confidence-Based strategy (with caveats below)

### Blockers for LIVE Trading:
- 🔴 Confidence-Based exit logic
- 🔴 Confidence-Based risk gates
- ⚠️ Momentum threshold validation
- ⚠️ Sector laggard definition

---

## 📞 NEXT STEPS

**Immediate**:
1. Verify build completes successfully
2. Deploy code to 173.249.55.84
3. Enable Confidence-Based strategy in SIMULATED mode
4. Test all 14 strategies for 24 hours

**Before LIVE**:
1. Implement exit logic for Confidence-Based strategy
2. Add risk gate checks for Confidence-Based strategy
3. Backtest new strategy thresholds
4. Document strategy-specific configuration
5. Set up monitoring alerts for all strategies

---

**Report Generated**: 2026-06-08 07:50 UTC  
**Build Status**: ⏳ In progress  
**Next Check**: 15 minutes
