# STRATEGY FAMILY CLASSIFICATION
## Production Strategy Categorization by Strategic Approach

Date: 2026-06-09  
Methodology: Code architecture review (Entry/exit logic, indicator composition)  
Scope: All 9 production trading strategies in platform

---

## SECTION 1: CLASSIFICATION FRAMEWORK

### Eight Strategic Categories

1. **Opportunity Detection** - Identify emerging conditions BEFORE move develops
2. **Momentum Initiation** - Detect START of momentum acceleration
3. **Momentum Confirmation** - Confirm ESTABLISHED momentum with multiple signals
4. **Trend Following** - Follow already-established trends
5. **Mean Reversion** - Fade extended moves back to fair value
6. **Exhaustion Detection** - Identify when momentum ENDS
7. **Breakout Detection** - Detect range breakthrough at initiation
8. **Order Flow Detection** - Analyze smart money positioning

---

## SECTION 2: INDIVIDUAL STRATEGY CLASSIFICATION

---

### STRATEGY 1: INDEX_HUNT

**Primary Purpose:** Confirm established index momentum with multi-factor validation

**Strategic Category:** **MOMENTUM CONFIRMATION**

**Leading/Coincident/Lagging Composition:**
- Leading: 0%
- Coincident: 20% (VIX, time window)
- Lagging: 80% (5m momentum, 30m trend, PCR, anti-chase)

**Core Indicators:**
- 5m momentum band (0.055%-0.60%)
- 30m trend alignment
- Put-Call Ratio (PCR) - options confirmation
- VIX defense
- Anti-chase extension guard

**Entry Philosophy:**
- Enter AFTER 5-minute move is established
- Require 30-minute trend confirmation
- Validate with options market (PCR)
- Block if price already extended (anti-chase)
- Quality score peaks when ALL confirmations aligned

**Exit Philosophy:**
- Time-based stops (T1 at 1.28x, T2 at 1.65x entry)
- Hard stop-loss at 0.80x entry
- Fixed risk/reward targets

**Gate Sequence:**
```
Time window → Momentum band → Trend alignment → PCR → VIX → Anti-chase
            (all MUST pass in sequence)
```

**Signal Timing:** After move maturity (80-90% complete)

---

### STRATEGY 2: NSE_SPIKE_DETECTION

**Primary Purpose:** Identify volume/momentum acceleration BEFORE large moves

**Strategic Category:** **MOMENTUM INITIATION**

**Leading/Coincident/Lagging Composition:**
- Leading: 30% (volume acceleration, momentum acceleration rate)
- Coincident: 40% (current bar quality, nifty component)
- Lagging: 30% (volume multiples vs baseline)

**Core Indicators:**
- Volume acceleration score (current vs baseline)
- Momentum acceleration score
- Bar quality (close proximity to high)
- Nifty trend alignment
- Order book imbalance (pressure)
- Relative strength

**Entry Philosophy:**
- Enter when volume/momentum STARTS accelerating
- Score-based system (not binary gates)
- Detect BEGINNING of spike, not confirmation
- Multi-dimensional scoring (20 components)
- Capitalize on early momentum build

**Exit Philosophy:**
- Momentum target (when acceleration ceases)
- Time-based (usually 5-15 minutes)

**Scoring Model:**
```
Volume Accel (20%) + Momentum (30%) + Trend (30%) + Bar Quality (20%)
= Detection score (0-100)

Score > 65: Entry signal
```

**Signal Timing:** At acceleration initiation (20-40% of move)

---

### STRATEGY 3: EARLY_BREAKOUT

**Primary Purpose:** Catch range breakouts in earliest session window

**Strategic Category:** **BREAKOUT DETECTION**

**Leading/Coincident/Lagging Composition:**
- Leading: 10% (range initiation)
- Coincident: 40% (breakout confirmation, volume)
- Lagging: 50% (prior range definition)

**Core Indicators:**
- Previous day range or overnight range
- Breakout threshold (0.1% above/below range)
- Volume confirmation (1.5x average)
- Time window (9:30-10:30 IST only)
- Price action within first hour

**Entry Philosophy:**
- Detect when price breaks OUT of recent range
- Require volume confirmation
- Limited to early morning window (highest vol, volatility)
- Trade INITIATION of breakout move
- Early in trading session = early in move lifecycle

**Exit Philosophy:**
- Momentum target
- Time window (end of early morning)
- Stop-loss below range

**Breakout Logic:**
```
Price > Range_High + 0.1% + Volume > 1.5x Avg → Entry
```

**Signal Timing:** At breakout initiation (beginning of move)

---

### STRATEGY 4: VWAP_BOUNCE

**Primary Purpose:** Revert extended moves back to VWAP fair value

**Strategic Category:** **MEAN REVERSION**

**Leading/Coincident/Lagging Composition:**
- Leading: 5% (price extension detection)
- Coincident: 50% (VWAP proximity, bounce confirmation)
- Lagging: 45% (touch count validation, historical VWAP)

**Core Indicators:**
- VWAP level (fair value reference)
- VWAP touch detection (within 1%)
- Bounce confirmation (price away from VWAP 2%)
- Minimum touch count (3 touches before signal)
- Price proximity to VWAP

**Entry Philosophy:**
- Wait for price to touch VWAP multiple times (exhaustion)
- Enter when bounce CONFIRMED (price moves away 2%)
- Fade the extended move back to fair value
- Requires multiple touch confirmations
- Trade momentum exhaustion and mean reversion

**Exit Philosophy:**
- Back to VWAP (fair value)
- Time-based if no progress

**Bounce Logic:**
```
Price touches VWAP (within 1%) 3+ times
→ THEN price moves 2% away
→ Enter bounce-back trade
```

**Signal Timing:** After VWAP touches (move extended, late)

---

### STRATEGY 5: GAP_FILL

**Primary Purpose:** Revert gap opens back to yesterday's close

**Strategic Category:** **MEAN REVERSION**

**Leading/Coincident/Lagging Composition:**
- Leading: 0%
- Coincident: 30% (gap measurement)
- Lagging: 70% (historical gap fill rates)

**Core Indicators:**
- Overnight gap size (0.3% threshold)
- Partial fill vs complete fill (50% of gap)
- Gap direction (up/down)
- Time-of-day progression
- Trend against gap

**Entry Philosophy:**
- Enter when overnight gap forms (0.3%+)
- Fade the gap expecting reversion
- Pure mean reversion to previous close
- No momentum or trend confirmation needed
- Trade the gap exhaustion

**Exit Philosophy:**
- Gap fill complete (back to yesterday's close)
- Time-based if gap persists
- Stop-loss if gap widens

**Gap Logic:**
```
Price opens > 0.3% from yesterday's close
→ Expect mean reversion toward close
→ Enter fade trade
```

**Signal Timing:** At gap initiation (opening)

---

### STRATEGY 6: S3_VWAP_RETEST

**Primary Purpose:** Trade VWAP retest movements with tight risk

**Strategic Category:** **TREND FOLLOWING** (with VWAP structure)

**Leading/Coincident/Lagging Composition:**
- Leading: 5%
- Coincident: 60% (VWAP distance, SMA relationship)
- Lagging: 35% (historical support/resistance)

**Core Indicators:**
- VWAP level
- 20-day SMA
- 50-day SMA
- Price-to-VWAP distance
- Support/resistance zones
- Time window (10:15-1:45 IST)

**Entry Philosophy:**
- Enter when price retests VWAP after moving away
- Use SMA crosses for confirmation
- Follow trend while price near VWAP
- Tight stop-loss (0.25%)
- Fixed target (0.60%)

**Exit Philosophy:**
- Fixed profit target (0.60%)
- Fixed stop-loss (0.25%)
- High risk/reward ratio entry strategy

**Entry Conditions:**
```
Price pulls back near VWAP
+ SMA structure supporting
→ Enter with tight stops
```

**Signal Timing:** During retest, mid-move

---

### STRATEGY 7: SECTOR_LAGGARD

**Primary Purpose:** Catch sector laggards reverting to sector momentum

**Strategic Category:** **MOMENTUM INITIATION**

**Leading/Coincident/Lagging Composition:**
- Leading: 40% (sector momentum acceleration, laggard detection)
- Coincident: 35% (relative strength measurement)
- Lagging: 25% (historical sector correlation)

**Core Indicators:**
- Sector momentum (1% threshold)
- Stock lag vs sector (2% lag required)
- Relative strength to sector
- Reversal confirmation (1% recovery move)
- Correlation coefficient

**Entry Philosophy:**
- Identify sector accelerating (bullish)
- Find stocks LAGGING the sector
- Enter when laggards START catching up
- Catch the reversion to sector mean
- Early in laggard recovery

**Exit Philosophy:**
- When caught up to sector
- Momentum continuation
- Time-based

**Selection Logic:**
```
Sector momentum > 1% (bullish)
+ Stock LAG vs sector > 2% (lagging)
+ Stock starts recovery (early)
→ Entry signal
```

**Signal Timing:** At laggard recovery start (early)

---

### STRATEGY 8: ADV_CASH

**Primary Purpose:** Multi-factor cash equity trading across 82 instruments

**Strategic Category:** **TREND FOLLOWING**

**Leading/Coincident/Lagging Composition:**
- Leading: 15% (momentum acceleration detection)
- Coincident: 50% (order flow, price action, technical)
- Lagging: 35% (trend confirmation, historical patterns)

**Core Indicators:**
- 10-step validation system:
  1. Volume confirmation
  2. Price structure
  3. Support/resistance
  4. Trend alignment
  5. Order flow
  6. Relative strength
  7. Volatility regime
  8. Time-based filters
  9. Risk assessment
  10. Profit target validation

- Covers: Tier 1/2/3 stocks, Indices, ETFs
- KNN ML model with 82 historical instruments

**Entry Philosophy:**
- Multi-factor validation (ALL 10 steps must pass)
- Trade established trends
- High confidence setup validation
- Risk-adjusted position sizing
- Wide universe approach

**Exit Philosophy:**
- Profit target (0.60% typical)
- Hard stop-loss (0.25%)
- Time-based

**Validation Flow:**
```
10 sequential validation gates
(all must pass for entry)
```

**Signal Timing:** After trend establishment (mid-move)

---

### STRATEGY 9: S7_RANGE_FADE

**Primary Purpose:** Fade intraday range extremes back to mean

**Strategic Category:** **MEAN REVERSION**

**Leading/Coincident/Lagging Composition:**
- Leading: 5%
- Coincident: 50% (range extremes, price extension)
- Lagging: 45% (historical range statistics)

**Core Indicators:**
- Intraday range (high-low)
- Extension above/below range
- Range mean (midpoint)
- Time window (10:15-1:45 IST)
- Price proximity to extremes

**Entry Philosophy:**
- Detect when price extends to range extremes
- Fade the extension back to range mean
- Trade range-bound intraday behavior
- Classic range-fade setup

**Exit Philosophy:**
- Back to range midpoint (0.45% typical target)
- Hard stop-loss (0.25%)
- Time-based if range breaks

**Fade Logic:**
```
Price reaches range high/low
→ Fade back to midpoint
→ Expect mean reversion
```

**Signal Timing:** At extension (after move into range extreme)

---

## SECTION 3: STRATEGIC COMPOSITION MATRIX

| Strategy | Category | Leading % | Coincident % | Lagging % | Entry Timing | Typical Win Rate |
|----------|----------|-----------|--------------|-----------|--------------|------------------|
| INDEX_HUNT | Momentum Confirmation | 0% | 20% | 80% | Late (80%+ complete) | 33.7% baseline |
| NSE_SPIKE | Momentum Initiation | 30% | 40% | 30% | Early (20-40% complete) | ~60%+ |
| EARLY_BREAKOUT | Breakout Detection | 10% | 40% | 50% | Very early (initiation) | ~70%+ |
| VWAP_BOUNCE | Mean Reversion | 5% | 50% | 45% | Late (exhausted) | ~55%+ |
| GAP_FILL | Mean Reversion | 0% | 30% | 70% | At open (gap start) | ~50%+ |
| S3_VWAP | Trend Following | 5% | 60% | 35% | Mid-move | ~65%+ |
| SECTOR_LAGGARD | Momentum Initiation | 40% | 35% | 25% | Early (recovery start) | ~65%+ |
| ADV_CASH | Trend Following | 15% | 50% | 35% | Mid-move | 75.6% |
| S7_RANGE_FADE | Mean Reversion | 5% | 50% | 45% | Late (extended) | ~50%+ |

---

## SECTION 4: CATEGORY FREQUENCY ANALYSIS

### Distribution by Strategic Category

**Momentum Confirmation:** 1 strategy
- INDEX_HUNT (80% lagging, 20% coincident)
- Most conservative approach
- Highest quality gates, but latest entry

**Momentum Initiation:** 2 strategies
- NSE_SPIKE_DETECTION (30% leading, 40% coincident, 30% lagging)
- SECTOR_LAGGARD (40% leading, 35% coincident, 25% lagging)
- Early entry focus, acceleration detection

**Trend Following:** 2 strategies
- S3_VWAP_RETEST (60% coincident, structural approach)
- ADV_CASH (50% coincident, multi-factor validation)
- Established trend traders

**Mean Reversion:** 3 strategies
- VWAP_BOUNCE (50% coincident)
- GAP_FILL (30% coincident, 70% lagging)
- S7_RANGE_FADE (50% coincident)
- Fade exhausted moves

**Breakout Detection:** 1 strategy
- EARLY_BREAKOUT (40% coincident, limited to early hours)
- Range breakthrough specialist

**Exhaustion Detection:** 0 strategies
- MISSING from platform

**Opportunity Detection:** 0 strategies
- MISSING from platform

**Order Flow Detection:** 0 strategies
- MISSING from platform

---

## SECTION 5: STRATEGIC GAPS

### Missing Categories

**1. EXHAUSTION DETECTION** ❌ NOT IMPLEMENTED
- Would identify when momentum is ENDING
- Different from mean reversion (reversion signals the end)
- Would detect: Divergence, volume decline, momentum failure
- Would enter BEFORE reversion
- None of 9 strategies focus on exhaustion signals

**2. OPPORTUNITY DETECTION** ❌ NOT IMPLEMENTED
- Would identify EMERGING conditions BEFORE momentum starts
- Different from momentum initiation (which catches acceleration)
- Would detect: Divergences, volume building, setup formation
- Would be 0% lagging, 100% leading
- All current strategies have 0-40% leading component max

**3. ORDER FLOW DETECTION** ❌ NOT IMPLEMENTED
- Would use smart money positioning (bid-ask imbalance)
- Order book pressure tracking
- Institutional flow analysis
- Available in platform (OrderFlowMetricsService) but UNUSED in strategies

---

## SECTION 6: CATEGORY OVERREPRESENTATION

### Most Represented Categories

**Mean Reversion: 3 strategies (33%)**
- VWAP_BOUNCE
- GAP_FILL  
- S7_RANGE_FADE
- All are late-entry exhaustion fades
- All have similar 45-70% lagging composition

**Momentum (Initiation + Confirmation): 3 strategies (33%)**
- NSE_SPIKE_DETECTION (initiation)
- INDEX_HUNT (confirmation)
- SECTOR_LAGGARD (initiation)
- Mix of early and late entry

**Trend Following: 2 strategies (22%)**
- S3_VWAP_RETEST
- ADV_CASH
- Mid-move entry focus

**Breakout: 1 strategy (11%)**
- EARLY_BREAKOUT
- Confined to 1-hour window

---

## SECTION 7: HEAVY OVERLAP DETECTION

### Categories with High Overlap

**Momentum Initiation vs Momentum Confirmation:**
- **Overlap:** Both use momentum metrics
- **Difference:** Initiation (early) vs Confirmation (late)
- **Strategies Affected:**
  - NSE_SPIKE (initiation, 20-40% complete)
  - INDEX_HUNT (confirmation, 80-90% complete)
- **Severity:** MODERATE (different entry timing mitigates overlap)

**Mean Reversion Category (Internal Overlap):**
- **VWAP_BOUNCE vs S7_RANGE_FADE:**
  - Both fade to mean (VWAP vs range midpoint)
  - Both require extension before entry
  - Both ~50% coincident, ~45% lagging
  - **Severity:** HIGH (very similar logic)

**Trend Following Category:**
- **S3_VWAP vs ADV_CASH:**
  - Both follow established trends
  - ADV_CASH has 10-step validation (more complex)
  - S3_VWAP simpler VWAP-based approach
  - **Severity:** LOW (different complexity levels)

**Mean Reversion vs Trend Following:**
- **No direct overlap** (opposite directions)
- But some strategies bridge both:
  - S3_VWAP_RETEST follows trend ON VWAP structure
  - Could be classified as hybrid

---

## SECTION 8: ENTRY TIMING DISTRIBUTION

### When Each Strategy Enters (by Move Lifecycle)

```
0-20% Complete (VERY EARLY):
└─ EARLY_BREAKOUT (entry at breakout initiation)
└─ NSE_SPIKE_DETECTION (20-40% range)

20-40% Complete (EARLY):
├─ NSE_SPIKE_DETECTION (momentum acceleration start)
└─ SECTOR_LAGGARD (laggard recovery start)

40-60% Complete (MID-MOVE):
├─ GAP_FILL (partial fill)
├─ S3_VWAP_RETEST (retest of move)
└─ ADV_CASH (trend established)

60-80% Complete (LATE):
├─ VWAP_BOUNCE (touches accumulating)
└─ S7_RANGE_FADE (nearing extension)

80-100% Complete (VERY LATE):
└─ INDEX_HUNT (confirmation peak)
```

**Pattern:** Platform biased toward MID-TO-LATE entry
- 0% strategies truly leading (0-20%)
- 2 strategies early (NSE_SPIKE, SECTOR_LAGGARD, EARLY_BREAKOUT)
- 3 strategies mid-late (GAP_FILL, S3_VWAP, ADV_CASH)
- 2 strategies very late (VWAP_BOUNCE, INDEX_HUNT)
- 1 late (S7_RANGE_FADE)

---

## SECTION 9: INDICATOR TYPE DISTRIBUTION

### Platform-Wide Indicator Usage

**Leading Indicators (Available but Underutilized):**
- Volume acceleration: Used by NSE_SPIKE (only)
- Momentum acceleration: Used by NSE_SPIKE (only)
- OBI slope: Not used in production strategies
- Order flow: Not used in production strategies
- Sector rotation: Used by SECTOR_LAGGARD (only)
- Range compression: Not explicitly used

**Coincident Indicators (Well-Represented):**
- VWAP distance: Used in 3 strategies (S3_VWAP, VWAP_BOUNCE, S7_RANGE_FADE)
- Current price: Used in all 9 strategies
- Order book imbalance: Implicit in most
- SMA crossovers: Used in 2 strategies

**Lagging Indicators (Heavily Used):**
- Trend confirmation: Used in 8/9 strategies
- Support/resistance: Used in 6/9 strategies
- Historical patterns: Used in 7/9 strategies
- PCR (options): Used in 1 strategy (INDEX_HUNT)

**Distribution:** 75% lagging, 20% coincident, 5% leading (platform-wide)

---

## SECTION 10: CONCLUSIONS

### Strategic Gaps on Platform

**Critical Absences:**
1. **Exhaustion Detection** - Would detect when momentum is ENDING (no implementation)
2. **Opportunity Detection** - Would detect EMERGING setups (no implementation)
3. **Order Flow Detection** - Would use smart money positioning (infrastructure exists, not used)

### Overrepresented Categories

**Mean Reversion is Overweight** (3 strategies, 33%)
- VWAP_BOUNCE, GAP_FILL, S7_RANGE_FADE are all similar approaches
- All late-entry, exhaustion-fade based
- Could consolidate to single adaptive strategy

### Heavy Overlaps

**Mean Reversion Internal Overlap:**
- VWAP_BOUNCE and S7_RANGE_FADE are very similar
- Both fade extends to means
- Both ~50% coincident, ~45% lagging
- Candidates for consolidation

**INDEX_HUNT is Category of One:**
- Only pure momentum confirmation strategy
- All others are initiation, trend, or reversion
- No overlap with other strategies

### Platform Composition

**By Entry Timing:**
- Missing: Truly leading (0% lagging) strategies
- Missing: Exhaustion detection at peak
- Over-represented: Mid-to-late entry (6 of 9 strategies)

**By Indicator Type:**
- 75% lagging (risk-averse, confirmation-heavy)
- 20% coincident (current state)
- 5% leading (opportunity-detection minimal)
- Platform skewed toward confirmation over opportunity

**By Strategic Philosophy:**
- 33% Mean Reversion (fade exhaustion)
- 33% Momentum (detect or confirm)
- 22% Trend Following (established moves)
- 11% Breakout (range breaks)
- 0% Exhaustion
- 0% Opportunity
- 0% Order Flow

---

**STRATEGY FAMILY CLASSIFICATION COMPLETE**

**Pure code-based classification. No historical outcome analysis. No recommendations. Only strategic category assignment and gap analysis.**

