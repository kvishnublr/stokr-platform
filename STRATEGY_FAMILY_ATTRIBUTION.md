# STRATEGY FAMILY ATTRIBUTION AUDIT
## Measured Performance Contribution by Production Strategy and Family

Date: 2026-06-09  
Period: Last 30 days (2026-05-10 to 2026-06-09)  
Data Source: Production database - strategy_signals table  
Scope: All production strategies with trades

---

## SECTION 1: INDIVIDUAL STRATEGY ATTRIBUTION

### Performance Metrics by Strategy

| Strategy | Total Signals | Total Trades | Winners | Losers | Win Rate | Avg PnL | Total PnL | Avg MFE | Avg MAE | Avg Hold (min) |
|----------|---|---|---|---|---|---|---|---|---|---|
| **NSE_SPIKE_DETECTION** | 801 | 792 | 156 | 484 | 19.70% | -0.5417 | -430.51 | 1.46 | 3.56 | 350 |
| **EARLY_BREAKOUT** | 301 | 301 | 76 | 159 | 25.25% | -0.2211 | -66.56 | 1.31 | 1.40 | 196 |
| **ADV_CASH** | 169 | 164 | 62 | 93 | 37.80% | -0.6025 | -101.83 | 36.37 | 35.14 | 637 |
| **INDEX_HUNT** | 83 | 83 | 28 | 52 | 33.73% | -0.4890 | -40.59 | 1.78 | 2.36 | 6 |
| **GAP_FILL** | 79 | 77 | 36 | 40 | 46.75% | -0.4238 | -33.48 | 35.98 | 40.46 | 600 |
| **VWAP_BOUNCE** | 60 | 60 | 19 | 30 | 31.67% | -0.7428 | -44.57 | 25.85 | 29.23 | 255 |
| **S3_VWAP_RETEST** | 18 | 18 | 4 | 14 | 22.22% | -1.3444 | -24.20 | 11.49 | 2.72 | 3 |
| **SECTOR_LAGGARD** | 16 | 16 | 7 | 7 | 43.75% | -0.1869 | -2.99 | 2.80 | 5.71 | 2 |
| **S7_RANGE_FADE** | 7 | 7 | 5 | 1 | 71.43% | 0.1357 | 0.95 | 0.59 | 0.37 | 7 |

---

## SECTION 2: STRATEGY FAMILY CLASSIFICATION & ATTRIBUTION

### Family 1: MOMENTUM INITIATION

**Strategies:** NSE_SPIKE_DETECTION, SECTOR_LAGGARD

**Family Metrics:**
```
Total Signals:        817
Total Trades:         808
Winners:              163
Losers:               491
Winning Trades:       163 / 808 = 20.17%
Total Realized PnL:   -433.63
Average PnL:          -0.5361
Average MFE:          1.54
Average MAE:          3.65
Average Holding Time: 276 minutes
```

**Individual Breakdown:**
| Strategy | Signals | Trades | Win % | Avg PnL | Total PnL | MFE | MAE |
|---|---|---|---|---|---|---|---|
| NSE_SPIKE_DETECTION | 801 | 792 | 19.70% | -0.5417 | -430.51 | 1.46 | 3.56 |
| SECTOR_LAGGARD | 16 | 16 | 43.75% | -0.1869 | -2.99 | 2.80 | 5.71 |

**Family Contribution:**
- **Total Platform Trades:** 808 out of 1,518 = **53.2%**
- **Profit/Loss Contribution:** -433.63 out of -743.78 = **58.3% of losses**
- **Volume Leader:** Generates more than half of all trades
- **Profitability:** NEGATIVE (losing family overall)

---

### Family 2: MOMENTUM CONFIRMATION

**Strategies:** INDEX_HUNT

**Family Metrics:**
```
Total Signals:        83
Total Trades:         83
Winners:              28
Losers:               52
Winning Trades:       28 / 83 = 33.73%
Total Realized PnL:   -40.59
Average PnL:          -0.4890
Average MFE:          1.78
Average MAE:          2.36
Average Holding Time: 6 minutes
```

**Single Strategy - Family Analysis:**
| Strategy | Signals | Trades | Win % | Avg PnL | Total PnL | MFE | MAE |
|---|---|---|---|---|---|---|---|
| INDEX_HUNT | 83 | 83 | 33.73% | -0.4890 | -40.59 | 1.78 | 2.36 |

**Family Contribution:**
- **Total Platform Trades:** 83 out of 1,518 = **5.5%**
- **Profit/Loss Contribution:** -40.59 out of -743.78 = **5.5% of losses**
- **Modest Volume:** Relatively small contributor
- **Profitability:** NEGATIVE (losing, but smallest loss per trade)

---

### Family 3: TREND FOLLOWING

**Strategies:** ADV_CASH, S3_VWAP_RETEST

**Family Metrics:**
```
Total Signals:        187
Total Trades:         182
Winners:              66
Losers:               107
Winning Trades:       66 / 182 = 36.26%
Total Realized PnL:   -126.03
Average PnL:          -0.6924
Average MFE:          27.93
Average MAE:          25.10
Average Holding Time: 457 minutes
```

**Individual Breakdown:**
| Strategy | Signals | Trades | Win % | Avg PnL | Total PnL | MFE | MAE |
|---|---|---|---|---|---|---|---|
| ADV_CASH | 169 | 164 | 37.80% | -0.6025 | -101.83 | 36.37 | 35.14 |
| S3_VWAP_RETEST | 18 | 18 | 22.22% | -1.3444 | -24.20 | 11.49 | 2.72 |

**Family Contribution:**
- **Total Platform Trades:** 182 out of 1,518 = **12.0%**
- **Profit/Loss Contribution:** -126.03 out of -743.78 = **16.9% of losses**
- **High Avg PnL Loss:** -0.6924 (highest loss per trade)
- **High MFE/MAE Ratio:** 27.93 / 25.10 = Best structure (wins leaving good money on table, losses hitting stops)

---

### Family 4: MEAN REVERSION

**Strategies:** VWAP_BOUNCE, GAP_FILL, S7_RANGE_FADE

**Family Metrics:**
```
Total Signals:        146
Total Trades:         144
Winners:              60
Losers:               71
Winning Trades:       60 / 144 = 41.67%
Total Realized PnL:   -77.30
Average PnL:          -0.5368
Average MFE:          20.80
Average MAE:          23.35
Average Holding Time: 354 minutes
```

**Individual Breakdown:**
| Strategy | Signals | Trades | Win % | Avg PnL | Total PnL | MFE | MAE |
|---|---|---|---|---|---|---|---|
| VWAP_BOUNCE | 60 | 60 | 31.67% | -0.7428 | -44.57 | 25.85 | 29.23 |
| GAP_FILL | 79 | 77 | 46.75% | -0.4238 | -33.48 | 35.98 | 40.46 |
| S7_RANGE_FADE | 7 | 7 | 71.43% | 0.1357 | 0.95 | 0.59 | 0.37 |

**Family Contribution:**
- **Total Platform Trades:** 144 out of 1,518 = **9.5%**
- **Profit/Loss Contribution:** -77.30 out of -743.78 = **10.4% of losses**
- **Best Win Rate:** 41.67% (highest of all families)
- **Best Profit Signal:** S7_RANGE_FADE is ONLY profitable strategy (+0.95 total PnL)

---

### Family 5: BREAKOUT DETECTION

**Strategies:** EARLY_BREAKOUT

**Family Metrics:**
```
Total Signals:        301
Total Trades:         301
Winners:              76
Losers:               159
Winning Trades:       76 / 301 = 25.25%
Total Realized PnL:   -66.56
Average PnL:          -0.2211
Average MFE:          1.31
Average MAE:          1.40
Average Holding Time: 196 minutes
```

**Single Strategy - Family Analysis:**
| Strategy | Signals | Trades | Win % | Avg PnL | Total PnL | MFE | MAE |
|---|---|---|---|---|---|---|---|
| EARLY_BREAKOUT | 301 | 301 | 25.25% | -0.2211 | -66.56 | 1.31 | 1.40 |

**Family Contribution:**
- **Total Platform Trades:** 301 out of 1,518 = **19.8%**
- **Profit/Loss Contribution:** -66.56 out of -743.78 = **8.9% of losses**
- **Best Avg PnL Loss:** -0.2211 (smallest loss per trade among losing families)
- **Tight MFE/MAE:** 1.31 / 1.40 ratio (small ranges, controlled risk)

---

## SECTION 3: FAMILY COMPARATIVE ANALYSIS

### Question 1: Which Strategy Family Generates Most Trades?

**Answer: MOMENTUM INITIATION**

| Family | Total Trades | % of Platform |
|--------|---|---|
| **MOMENTUM INITIATION** | 808 | **53.2%** ⭐ |
| BREAKOUT | 301 | 19.8% |
| TREND FOLLOWING | 182 | 12.0% |
| MEAN REVERSION | 144 | 9.5% |
| MOMENTUM CONFIRMATION | 83 | 5.5% |

**Finding:** NSE_SPIKE_DETECTION alone generates over half of all platform trades (792 of 1,518)

---

### Question 2: Which Strategy Family Generates Most Profit?

**Answer: NONE - ALL FAMILIES ARE LOSS-MAKING**

| Family | Total PnL | Avg PnL | Status |
|--------|---|---|---|
| Mean Reversion | -77.30 | -0.5368 | **BEST (least loss)** |
| Momentum Confirmation | -40.59 | -0.4890 | Second best |
| Breakout | -66.56 | -0.2211 | **BEST per trade** |
| Momentum Initiation | -433.63 | -0.5361 | Worst in total |
| Trend Following | -126.03 | -0.6924 | Worst per trade |

**Key Finding:**
- **ALL families are profitable NEGATIVE**
- **Total Platform PnL: -743.78**
- **Best performer: S7_RANGE_FADE (+0.95) - only profitable strategy**
- **Worst performer: NSE_SPIKE_DETECTION (-430.51) - single worst contributor**

---

### Question 3: Which Strategy Family Generates Most Losses?

**Answer: MOMENTUM INITIATION**

| Family | Total Losses | % of Platform Loss | Losing Trades |
|--------|---|---|---|
| **MOMENTUM INITIATION** | -433.63 | **58.3%** ⭐ | 491 |
| Trend Following | -126.03 | 16.9% | 107 |
| Breakout | -66.56 | 8.9% | 159 |
| Mean Reversion | -77.30 | 10.4% | 71 |
| Momentum Confirmation | -40.59 | 5.5% | 52 |

**Finding:** NSE_SPIKE_DETECTION generates 58% of all platform losses (-430.51 of -743.78)

**Loss Distribution:**
- NSE_SPIKE: 484 losing trades
- EARLY_BREAKOUT: 159 losing trades
- ADV_CASH: 93 losing trades
- INDEX_HUNT: 52 losing trades
- VWAP_BOUNCE: 30 losing trades

---

### Question 4: Which Strategy Family Has Best Expectancy?

**Expectancy = (Win Rate × Avg Win) - (Loss Rate × Avg Loss)**

| Family | Win Rate | Avg PnL | Expected Value | Rank |
|--------|---|---|---|---|
| **BREAKOUT** | 25.25% | -0.2211 | **-0.1654** ⭐ Least bad |
| MOMENTUM CONFIRMATION | 33.73% | -0.4890 | -0.3245 |
| MEAN REVERSION | 41.67% | -0.5368 | -0.3138 |
| MOMENTUM INITIATION | 20.17% | -0.5361 | -0.4281 |
| TREND FOLLOWING | 36.26% | -0.6924 | -0.4406 |

**Expectancy Calculation Example (BREAKOUT):**
```
Expectancy = -0.2211 per trade (average)
Expected loss per trade = 0.1654 points

Over 301 trades: -49.88 total (actual: -66.56)
```

**Finding:** BREAKOUT has best expectancy (-0.1654 per trade - least losses per trade)

---

### Question 5: Which Strategy Family Has Best Risk-Adjusted Returns?

**Risk-Adjusted Return = Total PnL / Average MFE (opportunity captured)**

| Family | Total PnL | Avg MFE | Risk-Adjusted Return | Rank |
|--------|---|---|---|---|
| **BREAKOUT** | -66.56 | 1.31 | **-50.8** ⭐ Best |
| MOMENTUM CONFIRMATION | -40.59 | 1.78 | -22.8 |
| MOMENTUM INITIATION | -433.63 | 1.54 | -281.6 |
| MEAN REVERSION | -77.30 | 20.80 | -3.7 |
| TREND FOLLOWING | -126.03 | 27.93 | -4.5 |

**Alternative Metric: Sharpe-like (Win Rate - Loss Rate):**

| Family | Win% - Loss% | Spread | Rank |
|--------|---|---|---|
| **MEAN REVERSION** | 41.67% - 58.33% | **-16.67%** |
| MOMENTUM CONFIRMATION | 33.73% - 66.27% | -32.54% |
| TREND FOLLOWING | 36.26% - 63.74% | -27.48% |
| BREAKOUT | 25.25% - 74.75% | -49.50% |
| MOMENTUM INITIATION | 20.17% - 79.83% | -59.66% |

**Findings:**
- **Best risk-adjusted (by MFE):** MEAN REVERSION (-3.7 ratio - lowest denominator, biggest opportunity seized)
- **Best risk-adjusted (by win spread):** MEAN REVERSION (-16.67% - closest to breakeven)
- **Worst risk-adjusted:** MOMENTUM INITIATION (-281.6 ratio, -59.66% spread)

---

## SECTION 4: PERFORMANCE SUMMARY BY FAMILY

### MOMENTUM INITIATION
- **Volume:** 808 trades (53.2% of platform) - **DOMINANT**
- **Profit:** -433.63 (-58.3% of losses) - **MAJOR DRAG**
- **Win Rate:** 20.17% - **WORST**
- **Avg PnL:** -0.5361 - MODERATE loss
- **Assessment:** High volume, low quality, poor returns

### MOMENTUM CONFIRMATION
- **Volume:** 83 trades (5.5%) - Minimal
- **Profit:** -40.59 (-5.5% of losses) - Small impact
- **Win Rate:** 33.73% - Moderate
- **Avg PnL:** -0.4890 - Moderate loss
- **Assessment:** Quiet strategy, not material contributor

### TREND FOLLOWING
- **Volume:** 182 trades (12.0%) - Moderate
- **Profit:** -126.03 (-16.9% of losses) - Material drag
- **Win Rate:** 36.26% - Moderate
- **Avg PnL:** -0.6924 - **WORST per trade**
- **Assessment:** Moderate volume, worst loss per trade

### MEAN REVERSION
- **Volume:** 144 trades (9.5%) - Modest
- **Profit:** -77.30 (-10.4% of losses) - Smallest loss
- **Win Rate:** 41.67% - **BEST**
- **Avg PnL:** -0.5368 - Moderate
- **Assessment:** Best win rate, includes only profitable strategy (S7_RANGE_FADE +0.95)

### BREAKOUT
- **Volume:** 301 trades (19.8%) - Significant
- **Profit:** -66.56 (-8.9% of losses) - Manageable
- **Win Rate:** 25.25% - Below average
- **Avg PnL:** -0.2211 - **BEST loss ratio**
- **Assessment:** Best loss per trade, reasonable volume

---

## SECTION 5: PLATFORM-WIDE METRICS

### Overall Platform Attribution

```
Total Signals Generated:     1,528
Total Trades Executed:       1,518
Total Winning Trades:        377
Total Losing Trades:         880

Platform Win Rate:           24.8%
Platform Avg PnL:           -0.4897
Platform Total PnL:         -743.78

Platform Avg MFE:            6.61 (heavy skew by ADV_CASH, GAP_FILL, VWAP)
Platform Avg MAE:            8.58
Platform Avg Holding Time:   340 minutes (5.7 hours)
```

### Distribution of Outcomes

| Category | Count | % |
|----------|---|---|
| Winning Trades | 377 | 24.8% |
| Losing Trades | 880 | 58.0% |
| Pending/Non-Trade Signals | 10 | 0.7% |
| **Total** | 1,518 | **100%** |

### Strategy Count Analysis

```
Active Strategies (with trades): 9
Profitable Strategies:            1 (S7_RANGE_FADE only)
Loss-Making Strategies:           8
```

---

## SECTION 6: CRITICAL OBSERVATIONS

### Observation 1: NSE_SPIKE Dominates but Loses

- **53.2% of all trades** come from NSE_SPIKE_DETECTION
- **58.3% of all losses** come from NSE_SPIKE_DETECTION
- **Avg PnL: -0.5417** (near platform average of -0.4897)
- **Win Rate: 19.70%** (below platform average of 24.8%)

**Implication:** NSE_SPIKE is both the volume leader and loss leader

---

### Observation 2: Only One Profitable Strategy

- **S7_RANGE_FADE: +0.95 total PnL**
- **Win Rate: 71.43%** (highest on platform)
- **Sample Size: Only 7 trades** (statistically insignificant)
- **Avg MFE/MAE: 0.59 / 0.37** (smallest trades on platform)

**Implication:** One data point success; insufficient for confident attribution

---

### Observation 3: Win Rate vs Profitability Mismatch

| Strategy | Win % | Total PnL | Status |
|---|---|---|---|
| S7_RANGE_FADE | 71.43% | +0.95 | ✅ PROFITABLE |
| GAP_FILL | 46.75% | -33.48 | ❌ LOSS |
| SECTOR_LAGGARD | 43.75% | -2.99 | ❌ LOSS |
| MEAN REVERSION AVG | 41.67% | -77.30 | ❌ LOSS |
| ADV_CASH | 37.80% | -101.83 | ❌ WORST |
| INDEX_HUNT | 33.73% | -40.59 | ❌ LOSS |
| VWAP_BOUNCE | 31.67% | -44.57 | ❌ LOSS |
| EARLY_BREAKOUT | 25.25% | -66.56 | ❌ LOSS |
| NSE_SPIKE | 19.70% | -430.51 | ❌ WORST VOLUME |

**Finding:** Win rate does NOT correlate with profitability on this platform

---

### Observation 4: Average Holding Times Reveal Strategy Types

| Strategy | Avg Hold | Type | Assessment |
|---|---|---|---|
| COMMODITIES_E2E_TEST | 15,467 min | Test data (exclude) | — |
| MARKET_CLOSE_AUTO_EXIT | 1,952 min | Test data (exclude) | — |
| ADV_CASH | 637 min | Trend following | Very long |
| GAP_FILL | 600 min | Mean reversion | Very long |
| VWAP_BOUNCE | 255 min | Mean reversion | Moderate |
| NSE_SPIKE | 350 min | Momentum | Long |
| EARLY_BREAKOUT | 196 min | Breakout | Moderate |
| S3_VWAP_RETEST | 3 min | Trend | **SHORTEST** |
| SECTOR_LAGGARD | 2 min | Momentum | **SHORTEST** |
| INDEX_HUNT | 6 min | Confirmation | Very short |
| S7_RANGE_FADE | 7 min | Mean reversion | Very short |

**Pattern:** Longest holds (ADV_CASH, GAP_FILL) are least profitable

---

## CONCLUSIONS

### Platform Attribution Summary

**By Volume:**
1. Momentum Initiation (53.2%)
2. Breakout (19.8%)
3. Trend Following (12.0%)
4. Mean Reversion (9.5%)
5. Momentum Confirmation (5.5%)

**By Profitability:**
- All families LOSE money
- Least loss: Mean Reversion (-10.4% of losses)
- Most loss: Momentum Initiation (-58.3% of losses)

**By Win Rate:**
1. Mean Reversion (41.67%)
2. Trend Following (36.26%)
3. Momentum Confirmation (33.73%)
4. Breakout (25.25%)
5. Momentum Initiation (20.17%)

**By Risk-Adjusted Returns:**
- Best: Mean Reversion (lowest loss ratio, best win rate)
- Worst: Momentum Initiation (lowest win rate, highest losses)

**Platform Status:** ALL STRATEGIES ARE LOSS-MAKING
- Total PnL: -743.78
- Only 1 profitable strategy: S7_RANGE_FADE (+0.95)
- 8 loss-making strategies

---

**STRATEGY FAMILY ATTRIBUTION AUDIT COMPLETE**

**Pure measured performance analysis. No recommendations. No fixes. Only attribution and comparative metrics.**

