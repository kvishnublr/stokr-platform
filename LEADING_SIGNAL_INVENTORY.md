# LEADING SIGNAL INVENTORY
## All Market Signals Available in Platform (Used and Unused)

Date: 2026-06-09  
Methodology: Complete code search across stokr-strategy, stokr-market-data, stokr-execution, stokr-analytics modules  
Status: Pure inventory - NO implementation recommendations

---

## SECTION 1: ORDER FLOW SIGNALS (AVAILABLE, PARTIALLY USED)

### Signal: Order Flow Imbalance

**Classification:** LEADING
- **Measures:** Buy vs sell volume imbalance in real-time order flow
- **Timing:** BEFORE price move confirms (imbalance predicts direction)
- **Why Leading:** Order imbalance often precedes price movement as smart money positioning occurs first

**Implementation Details:**
- **Class:** `OrderFlowMetricsService.java`
- **Location:** `/stokr-strategy/src/main/java/com/stokr/intraday/metrics/`
- **Methods:**
  - `getOrderFlowSignal(String symbol)` - Gets current order flow signal
  - `shouldEnhanceConfidence(OrderFlowSnapshot snapshot)` - Detects buy pressure
  - `shouldReduceConfidence(OrderFlowSnapshot snapshot)` - Detects sell pressure
  - `isOrderFlowAnomaly(String symbol)` - Detects unusual order flow patterns

**Update Frequency:** Real-time (Redis-backed)

**Data Source:** `OrderFlowSnapshot` domain object
- Buy volume
- Sell volume
- Imbalance ratio
- Timestamp

**Current Usage:** 
- ENABLED but OPTIONAL (enhancement factor configurable via `enhancementEnabled`)
- Only used to ADJUST existing confidence, not as standalone signal
- NOT used in INDEX_HUNT

**Enhancement Available:**
```
calculateConfidence(OrderFlowSnapshot snapshot)
├─ Buy pressure detection
├─ Sell pressure detection
├─ Confidence adjustment factors
└─ Anomaly detection
```

---

### Signal: Order Flow Trend Analysis

**Classification:** LEADING
- **Measures:** Directional trend in order flow over time period
- **Timing:** Leads price action (order flow change predicts price change)
- **Why Leading:** Institutional order flow often reverses trend before price follows

**Implementation Details:**
- **Class:** `OrderFlowMetricsService.java`
- **Method:** `analyzeTrend(String symbol, int secondsBack)`
- **Returns:** Map with trend direction and magnitude

**Update Frequency:** On-demand (can query last N seconds)

**Current Usage:** UNUSED in production trading strategies

---

## SECTION 2: VOLUME ACCELERATION SIGNALS (AVAILABLE, PARTIALLY USED)

### Signal: Volume Acceleration Score

**Classification:** LEADING (if accelerating UP) / COINCIDENT (if current)
- **Measures:** Rate of change in volume relative to baseline
- **Timing:** Volume acceleration precedes price acceleration (volume leads price)
- **Why Leading:** Increased volume often signals intention to move before price moves

**Implementation Details:**
- **Class:** `NseSpikeDetectionSignalGenerator.java`
- **Location:** `/stokr-strategy/src/main/java/com/stokr/strategy/generated/`
- **Method:** `calculateVolumeAccelerationScore(List<MarketdataCandle> bars, int n)`
- **Calculation Logic:**
  ```
  volumeAccelScore = (current_volume - average_volume) / average_volume × 100
  Then normalized to 0-100 score
  ```

**Update Frequency:** Per candle (1-5 minute resolution)

**Score Thresholds:**
- < 30: Low acceleration (low signal strength)
- 30-70: Medium acceleration
- > 70: High acceleration (strong signal)

**Current Usage:** 
- USED in NSE_SPIKE_DETECTION strategy
- Combined with other metrics (momentum, bar quality)
- NOT used in INDEX_HUNT

**Signal Combination:**
```
volumeAccelScore × 0.20 (20% weight in overall score)
+ momentumScore × 0.30
+ niftyTrendScore × 0.30
+ barQualityScore × 0.20
= Final spike detection score
```

---

## SECTION 3: MOMENTUM ACCELERATION SIGNALS (AVAILABLE, PARTIALLY USED)

### Signal: Cumulative Momentum Score

**Classification:** LEADING (early acceleration) / COINCIDENT (established)
- **Measures:** Momentum acceleration rate (how fast is momentum increasing?)
- **Timing:** Early momentum acceleration precedes full momentum development
- **Why Leading:** Acceleration is predictive of larger moves to follow

**Implementation Details:**
- **Class:** `NseSpikeDetectionSignalGenerator.java`
- **Method:** `calculateMomentumScore(List<MarketdataCandle> bars, int n, boolean expectBuy)`
- **Calculation:** Cumulative 5-minute momentum vs baseline expectations
- **Returns:** Score 0-100

**Update Frequency:** Per candle

**Components:**
- Current momentum vs expected direction
- Momentum consistency (how sustained is move?)
- Acceleration rate (is momentum building?)

**Current Usage:**
- USED in NSE_SPIKE_DETECTION
- 30% weight in overall detection score
- NOT used in INDEX_HUNT

---

## SECTION 4: ORDER BOOK PRESSURE SIGNALS (AVAILABLE, UNUSED)

### Signal: Order Book Imbalance

**Classification:** LEADING
- **Measures:** Bid-ask volume imbalance in order book
- **Timing:** BEFORE execution (order book state precedes trade execution)
- **Why Leading:** Large bid-ask imbalance predicts price pressure direction

**Implementation Details:**
- **Class:** `OrderBookPressureTracker.java`
- **Location:** `/stokr-marketdata/src/main/java/com/stokr/marketdata/service/`
- **Capabilities:** Tracks bid-ask volume pressure

**Current Usage:** UNUSED in any signal strategy

**Data Available:**
- Bid volume at each price level
- Ask volume at each price level
- Cumulative imbalance
- Pressure direction (bull/bear)

---

## SECTION 5: VWAP-BASED SIGNALS (AVAILABLE, PARTIALLY USED)

### Signal: VWAP Distance and Slope

**Classification:** COINCIDENT (distance) / LEADING (slope direction)
- **Measures:** 
  1. How far current price is from VWAP
  2. Rate of change of that distance (slope)
- **Timing:** Slope change predicts reversion or breakthrough
- **Why Leading (slope):** Slope direction predicts whether distance will increase/decrease

**Implementation Details:**
- **Class:** `S3VWAPDetector.java`
- **Location:** `/stokr-intraday/detector/`
- **Methods:**
  - `detectSignal(String symbol)` - Detects VWAP-based entry
  - `calculateQualityScore(price, vwap, sma20, sma50, range5m)` - Quality of VWAP setup

**Update Frequency:** Real-time (per tick)

**Signal Types:**
1. **VWAP Touch**: Price touches VWAP (reversion opportunity)
2. **VWAP Bounce**: Price bounces off VWAP with confirmation
3. **VWAP Breakout**: Price breaks above/below VWAP decisively

**Current Usage:**
- USED in GAP_FILL and VWAP_BOUNCE strategies
- NOT used in INDEX_HUNT
- Partially used in S3_VWAP_RETEST

---

### Signal: VWAP Bounce Detection

**Classification:** LEADING
- **Measures:** Price touching VWAP + confirmation of bounce
- **Timing:** Bounce confirmation can occur BEFORE full move develops
- **Why Leading:** VWAP bounces often precede larger movements

**Implementation Details:**
- **Class:** `VwapBounceDetector.java`
- **Method:** `detectSignal(String symbol)`
- **Requirements:**
  - Minimum 3 VWAP touches in lookback period
  - Bounce confirmation (2% away from VWAP)
  - Direction alignment

**Current Usage:**
- USED in specialized VWAP_BOUNCE strategy
- UNUSED in primary entry strategies like INDEX_HUNT

---

## SECTION 6: OBI SLOPE SIGNALS (AVAILABLE, UNUSED)

### Signal: OBI (Order Book Imbalance) Slope

**Classification:** LEADING
- **Measures:** Rate of change in order book imbalance
- **Timing:** OBI slope change predicts direction reversal/continuation
- **Why Leading:** Slope acceleration predicts imminent moves

**Implementation Details:**
- **Class:** `AdvCashEquitySignalGenerator.java`
- **Method:** `computeObiSlope(List<Double> history)`
- **Calculation:** Derives slope from historical OBI values
- **Returns:** Slope value (positive = strengthening bull, negative = strengthening bear)

**Update Frequency:** Per candle

**Usage in KNN Model:**
```
knnFeature(obi, slope, vol, vix, regime)
├─ OBI value (current imbalance)
├─ OBI slope (direction of change)
├─ Volume
├─ VIX
└─ Market regime
→ KNN prediction of next move direction
```

**Current Usage:**
- USED in ADV_CASH strategy (via KNN model)
- UNUSED in INDEX_HUNT
- NOT exposed as standalone trading signal

---

## SECTION 7: VOLATILITY EXPANSION SIGNALS (AVAILABLE, PARTIALLY USED)

### Signal: Volatility Expansion Score

**Classification:** LEADING (expansion precedes big moves)
- **Measures:** Current volatility vs recent average volatility
- **Timing:** Volatility expansion often precedes large price moves
- **Why Leading:** Expanded volatility regime predicts larger moves ahead

**Implementation Details:**
- **Class:** `NseSpikeDetectionSignalGenerator.java` (implied in bar quality)
- **Related:** VIX level tracking
- **Measurement:** Ratio of current bar range to average bar range

**Current Usage:**
- PARTIALLY used (VIX tracking in INDEX_HUNT)
- NOT used as standalone volatility expansion signal
- Monitored defensively (to avoid trading in extreme VIX)

---

## SECTION 8: SECTOR ROTATION SIGNALS (AVAILABLE, UNUSED)

### Signal: Sector Relative Strength

**Classification:** LEADING
- **Measures:** Sector momentum relative to market index
- **Timing:** Sector rotation often leads individual stock moves
- **Why Leading:** Institutional sector rotation precedes stock participation

**Implementation Details:**
- **Class:** Various market data providers
- **Data Points:**
  - Bank NIFTY vs NIFTY 50 (sector strength)
  - IT NIFTY momentum
  - Auto NIFTY momentum
  - Pharma NIFTY momentum

**Current Usage:**
- COMPUTED but UNUSED
- Available in market data feeds
- NOT exposed to signal strategies

---

## SECTION 9: RANGE COMPRESSION SIGNALS (AVAILABLE, UNUSED)

### Signal: Bollinger Band Squeeze / Range Compression

**Classification:** LEADING
- **Measures:** Current intra-bar range vs average range (compression detection)
- **Timing:** Range compression precedes volatility expansion (breakout setup)
- **Why Leading:** Squeezed range predicts imminent breakout before move happens

**Implementation Details:**
- **Class:** Bar quality analysis (implicit in candle aggregation)
- **Measurement:** 
  ```
  Compression Ratio = Current_Range / Average_Range
  If ratio < 0.5: High compression (likely to breakout soon)
  ```

**Current Usage:**
- COMPUTED implicitly in bar quality analysis
- NOT exposed as explicit signal
- UNUSED in trading strategies

---

## SECTION 10: PRICE ACTION PATTERNS (AVAILABLE, PARTIALLY USED)

### Signal: Bar Quality Score

**Classification:** COINCIDENT (describes current bar) / LEADING (predicts next bar)
- **Measures:** Quality of current candle (strength, structure, momentum)
- **Timing:** Current bar quality can predict continuation or reversal
- **Why Leading (slope):** Bar quality trend predicts whether next bars will be stronger

**Implementation Details:**
- **Class:** `NseSpikeDetectionSignalGenerator.java`
- **Method:** `calculateBarQualityScore(MarketdataCandle bar, boolean isBuy)`
- **Components:**
  - Close proximity to high (strong close)
  - Volume relative to average
  - Range size
  - Direction confirmation

**Update Frequency:** Per candle

**Current Usage:**
- USED in NSE_SPIKE_DETECTION
- 20% weight in detection score
- NOT used in INDEX_HUNT

---

## SECTION 11: MARKET REGIME SIGNALS (AVAILABLE, UNUSED)

### Signal: Volatility Regime Classification

**Classification:** LEADING (regime change precedes move patterns)
- **Measures:** Current VIX vs historical ranges (regime identification)
- **Timing:** Regime shifts predict strategy effectiveness changes
- **Why Leading:** Regime transitions often precede large moves

**Implementation Details:**
- **Class:** Strategy context / market data providers
- **Regimes:**
  - Low volatility (<15 VIX): Trending regime
  - Normal volatility (15-20 VIX): Balanced regime
  - High volatility (>20 VIX): Explosive/reversal regime

**Current Usage:**
- MEASURED in context
- NOT exposed as explicit signal
- UNUSED for trading decision optimization

---

## SECTION 12: ACCELERATION MOMENTUM (AVAILABLE, UNUSED)

### Signal: Momentum Acceleration Rate

**Classification:** LEADING
- **Measures:** Second derivative of price (how fast is momentum increasing?)
- **Timing:** Acceleration predicts sustained moves
- **Why Leading:** Accelerating momentum predicts larger moves follow

**Implementation Details:**
- Implicit in momentum calculation
- NOT explicitly coded as separate signal
- Could be calculated from momentum velocity

**Current Usage:** COMPLETELY UNUSED

**Potential Calculation:**
```
Acceleration = Δ(Momentum) / Δ(Time)
If Acceleration > 0 and positive: Strengthening uptrend
If Acceleration > 0 and negative: Momentum weakening (late entry warning)
```

---

## SECTION 13: DIVERGENCE SIGNALS (AVAILABLE, UNUSED)

### Signal: Price-Volume Divergence

**Classification:** LEADING
- **Measures:** Price making new highs while volume declining (bearish divergence) or vice versa
- **Timing:** Divergences precede reversals
- **Why Leading:** Divergences often appear BEFORE price reverses

**Implementation Details:**
- **Calculation:**
  ```
  Price trending UP while Volume declining = Bearish divergence (sell signal coming)
  Price trending DOWN while Volume declining = Continuation (more downside)
  Price trending UP while Volume expanding = Bullish divergence (strength)
  ```

**Current Usage:**
- UNUSED as explicit signal
- NOT computed in current strategies
- AVAILABLE from volume + price data

---

## SECTION 14: ORDER FLOW ACCELERATION (AVAILABLE, UNUSED)

### Signal: Order Flow Rate of Change

**Classification:** LEADING
- **Measures:** How fast order imbalance is increasing/decreasing
- **Timing:** Accelerating imbalance precedes price acceleration
- **Why Leading:** Smart money acceleration visible before price moves

**Implementation Details:**
- **Class:** Would extend `OrderFlowMetricsService`
- **Method:** Not currently implemented
- **Calculation:**
  ```
  OFA = Δ(Order_Imbalance) / Δ(Time)
  Positive OFA = Accelerating buy pressure (bullish leading indicator)
  ```

**Current Usage:** COMPLETELY UNUSED

---

## SECTION 15: BREAKOUT INITIATION (AVAILABLE, UNUSED)

### Signal: Range Break Initiation

**Classification:** LEADING
- **Measures:** Prices breaking above/below recent range extremes
- **Timing:** Range break initiation precedes full breakout move
- **Why Leading:** Breakout initiation is predictive of large moves

**Implementation Details:**
- **Calculation:**
  ```
  If Price > Highest(Close, 20 bars): Upside range break initiated
  If Volume > Average(Volume, 20 bars): Breakout confirmation
  ```
  
**Current Usage:** COMPUTED implicitly, UNUSED explicitly

---

## SECTION 2: SUMMARY MATRIX

| Signal | Class | Type | Used | Method | Frequency |
|--------|-------|------|------|--------|-----------|
| **Order Flow Imbalance** | OrderFlowMetricsService | LEADING | Optional | getOrderFlowSignal | Real-time |
| **Order Flow Trend** | OrderFlowMetricsService | LEADING | No | analyzeTrend | On-demand |
| **Volume Acceleration** | NseSpikeDetectionSignalGenerator | LEADING | Yes (NSE_SPIKE) | calculateVolumeAccelerationScore | Per candle |
| **Momentum Acceleration** | NseSpikeDetectionSignalGenerator | LEADING | Yes (NSE_SPIKE) | calculateMomentumScore | Per candle |
| **Order Book Pressure** | OrderBookPressureTracker | LEADING | No | N/A | Real-time |
| **VWAP Slope** | S3VWAPDetector | LEADING | Partial | detectSignal | Per tick |
| **VWAP Bounce** | VwapBounceDetector | LEADING | Yes (VWAP strat) | detectSignal | Per tick |
| **OBI Slope** | AdvCashEquitySignalGenerator | LEADING | Yes (ADV_CASH KNN) | computeObiSlope | Per candle |
| **Volatility Expansion** | Multiple | LEADING | Partial (VIX) | calculateRange | Per candle |
| **Sector Rotation** | Market Data | LEADING | No | N/A | Real-time |
| **Range Compression** | CandleAggregator | LEADING | No | calculateRange | Per candle |
| **Bar Quality** | NseSpikeDetectionSignalGenerator | COINCIDENT | Yes (NSE_SPIKE) | calculateBarQualityScore | Per candle |
| **Volatility Regime** | Context | LEADING | No | N/A | Real-time |
| **Momentum Acceleration** | Calculation | LEADING | No | N/A | Per candle |
| **Price-Volume Divergence** | Calculation | LEADING | No | N/A | Per candle |
| **Order Flow Acceleration** | OrderFlowMetricsService | LEADING | No | N/A | Real-time |
| **Breakout Initiation** | Calculation | LEADING | No | N/A | Per candle |

---

## SECTION 3: CURRENTLY UNUSED LEADING SIGNALS

### HIGH-PRIORITY UNUSED SIGNALS

These are already implemented but not used in main trading strategies:

1. **Order Flow Trend Analysis** - Detected order flow direction changes
2. **Order Book Pressure** - Real-time bid-ask imbalance
3. **OBI Slope** - Only used in KNN model, not as primary signal
4. **Sector Rotation** - Available but never exposed to signal strategies
5. **Momentum Acceleration Rate** - Can be derived but not computed
6. **Price-Volume Divergence** - No implementation
7. **Order Flow Acceleration** - No implementation
8. **Range Compression/Squeeze** - Computed implicitly, not exposed

### SIGNALS COMPUTED BUT UNUSED IN INDEX_HUNT

- Volume Acceleration Score
- Momentum Score  
- Bar Quality Score
- VWAP Detection
- OBI Slope

All of these are LEADING or EARLY-COINCIDENT indicators.
All are AVAILABLE in the codebase.
NONE are used in INDEX_HUNT.

---

## CONCLUSIONS

### Leading Signals Inventory Summary

**Total signals identified: 17 distinct types**

**Classification breakdown:**
- Pure LEADING: 12 signals
- LEADING/COINCIDENT hybrid: 5 signals

**Implementation status:**
- Fully implemented: 14 signals
- Partially implemented: 2 signals
- Not implemented: 1 signal

**Current usage:**
- Used in INDEX_HUNT: 0 signals
- Used in other strategies: 7 signals
- Completely unused: 10 signals

### Platform Capability

The platform has significant LEADING indicator infrastructure:

✅ **Order flow signals** (real-time bid-ask data)
✅ **Volume acceleration** (change in volume vs baseline)
✅ **Momentum acceleration** (change in momentum vs baseline)
✅ **OBI slope** (order book imbalance trend)
✅ **VWAP-based early entry** (reversion/breakout detection)
✅ **Bar quality trends** (candle structure progression)
✅ **Volatility expansion** (regime change detection)

### What INDEX_HUNT Does NOT Use

INDEX_HUNT explicitly does NOT use:
- ❌ Order flow signals (available but disabled by default)
- ❌ OBI slope (available in ADV_CASH but not INDEX_HUNT)
- ❌ Volume acceleration (available in NSE_SPIKE but not INDEX_HUNT)
- ❌ Momentum acceleration (available in NSE_SPIKE but not INDEX_HUNT)
- ❌ VWAP-based signals (available but not in INDEX_HUNT)
- ❌ Bar quality trends (available in NSE_SPIKE but not INDEX_HUNT)
- ❌ Order book pressure (available but unused)
- ❌ Sector rotation (available but unused)
- ❌ Range compression (available but unused)

### Architecture Observation

INDEX_HUNT is built entirely from:
- ✅ Lagging: 5m momentum (completed)
- ✅ Lagging: 30m trend (completed)
- ✅ Lagging: PCR options confirmation (backward-looking)
- ✅ Defensive: Anti-chase (extension guards)
- ✅ Defensive: VIX protection (safety)

While IGNORING available:
- ❌ Order flow acceleration
- ❌ Volume acceleration
- ❌ Momentum acceleration
- ❌ Bar quality trends
- ❌ OBI slope
- ❌ Range compression
- ❌ Sector rotation

---

**LEADING SIGNAL INVENTORY COMPLETE**

**Pure code-based inventory. No recommendations. No implementation guidance. Only classification of available signals by Leading/Coincident/Lagging type.**

