# NSE_SPIKE CLASSIFICATION VALIDATION
## Can Impulse Quality Be Determined at Signal Time or Only After Trade Outcome?

Date: 2026-06-09  
Methodology Review: Complete transparency on classification sources

---

## SECTION 1: CLASSIFICATION FORMULA (AS USED)

### Exact SQL Classification Logic

```sql
CASE
  WHEN max_favorable_excursion > max_adverse_excursion * 2 
    THEN 'SUSTAINABLE_IMPULSE'
  WHEN max_favorable_excursion > max_adverse_excursion * 1.2 
    THEN 'SUSTAINABLE_IMPULSE'
  WHEN realized_pnl > 0 AND max_favorable_excursion > 1.0 
    THEN 'SUSTAINABLE_IMPULSE'
  WHEN max_adverse_excursion > max_favorable_excursion * 3 
    THEN 'LIQUIDITY_EVENT'
  WHEN max_adverse_excursion > max_favorable_excursion * 1.5 AND realized_pnl < 0 
    THEN 'MEAN_REVERSION_TRAP'
  WHEN ABS(realized_pnl) < 0.1 AND max_favorable_excursion > 0.5 
    THEN 'TEMPORARY_SPIKE'
  WHEN max_favorable_excursion < 1.0 AND realized_pnl < 0 
    THEN 'TEMPORARY_SPIKE'
  ELSE 'UNCLASSIFIED'
END
```

---

## SECTION 2: INPUT SOURCES ANALYSIS

### Data Inputs Used in Classification

| Input | Source Table | Column Name | When Available | Trade Outcome Dependent |
|---|---|---|---|---|
| **max_favorable_excursion** | strategy_signals | max_favorable_excursion | AFTER trade exits | ✅ YES |
| **max_adverse_excursion** | strategy_signals | max_adverse_excursion | AFTER trade exits | ✅ YES |
| **realized_pnl** | strategy_signals | realized_pnl | AFTER trade exits | ✅ YES |

### Availability Timeline

```
Signal Generation (T=0):
  ├─ Strategy name: AVAILABLE
  ├─ Confidence score: NULL (98.2% of signals)
  ├─ Volume acceleration: AVAILABLE (used to generate signal)
  ├─ Momentum acceleration: AVAILABLE (used to generate signal)
  └─ max_favorable_excursion: NOT AVAILABLE
  └─ max_adverse_excursion: NOT AVAILABLE
  └─ realized_pnl: NOT AVAILABLE

Trade Execution (T=30-60 seconds):
  ├─ All above: Still not available
  ├─ Only entry_price is now available
  └─ MFE/MAE: Still accumulating

Trade in Progress (T=1-60 minutes):
  ├─ max_favorable_excursion: Increasing in real-time
  ├─ max_adverse_excursion: Increasing in real-time
  └─ realized_pnl: Changing in real-time

Trade Exit (T=100-500 minutes):
  ├─ outcome_time: NOW AVAILABLE
  ├─ exit_price: NOW AVAILABLE
  ├─ realized_pnl: NOW FINALIZED
  ├─ max_favorable_excursion: NOW FINALIZED
  ├─ max_adverse_excursion: NOW FINALIZED
  └─ Classification: NOW POSSIBLE
```

---

## SECTION 3: BUCKET-BY-BUCKET METHODOLOGY VALIDATION

### BUCKET 1: SUSTAINABLE_IMPULSE

**Classification Rule:**
```sql
max_favorable_excursion > max_adverse_excursion * 2 OR
max_favorable_excursion > max_adverse_excursion * 1.2 OR
(realized_pnl > 0 AND max_favorable_excursion > 1.0)
```

**Inputs Required:**
| Input | Status | Available At Signal | Available During Trade | Available At Exit |
|---|---|---|---|---|
| max_favorable_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |
| max_adverse_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |
| realized_pnl | OUTCOME DATA | ❌ NO | ❌ NO | ✅ YES |

**Can This Be Determined at Signal Time?** ❌ NO

**Why?**
- Requires knowing final MFE/MAE which only exist after trade completes
- Requires knowing realized_pnl which only exists after exit
- Classification is **RETROACTIVE** (looking backward at completed trade)

**When Can It Be Determined?** ✅ Only after trade completes (outcome_time)

---

### BUCKET 2: LIQUIDITY_EVENT

**Classification Rule:**
```sql
max_adverse_excursion > max_favorable_excursion * 3
```

**Inputs Required:**
| Input | Status | Available At Signal | Available During Trade | Available At Exit |
|---|---|---|---|---|
| max_adverse_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |
| max_favorable_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |

**Can This Be Determined at Signal Time?** ❌ NO

**Why?**
- Requires knowing final MFE/MAE ratio
- Cannot know if adverse will be 3x favorable until trade completes
- Classification is **RETROACTIVE**

**When Can It Be Determined?** 
- ⚠️ Partially during trade: If MAE grows to 3x+ current MFE, could flag as LIQUIDITY_EVENT in real-time
- ✅ Definitively after trade completes

---

### BUCKET 3: MEAN_REVERSION_TRAP

**Classification Rule:**
```sql
max_adverse_excursion > max_favorable_excursion * 1.5 AND realized_pnl < 0
```

**Inputs Required:**
| Input | Status | Available At Signal | Available During Trade | Available At Exit |
|---|---|---|---|---|
| max_adverse_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |
| max_favorable_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |
| realized_pnl | OUTCOME DATA | ❌ NO | ❌ NO | ✅ YES |

**Can This Be Determined at Signal Time?** ❌ NO

**Why?**
- Requires knowing final realized_pnl
- Requires knowing final MFE/MAE ratio
- **Explicitly requires trade outcome**

**When Can It Be Determined?** ✅ Only after trade exits and PnL is realized

---

### BUCKET 4: TEMPORARY_SPIKE

**Classification Rule:**
```sql
(ABS(realized_pnl) < 0.1 AND max_favorable_excursion > 0.5) OR
(max_favorable_excursion < 1.0 AND realized_pnl < 0)
```

**Inputs Required:**
| Input | Status | Available At Signal | Available During Trade | Available At Exit |
|---|---|---|---|---|
| realized_pnl | OUTCOME DATA | ❌ NO | ❌ NO | ✅ YES |
| max_favorable_excursion | OUTCOME DATA | ❌ NO | ⚠️ Accumulating | ✅ YES |

**Can This Be Determined at Signal Time?** ❌ NO

**Why?**
- Requires realized_pnl (outcome only)
- Requires final max_favorable_excursion (outcome only)
- Classification is **RETROACTIVE**

**When Can It Be Determined?** ✅ Only after trade completes

---

## SECTION 4: CRITICAL FINDING

### The Classification Validity Question

**Can these impulse quality classifications be determined at signal time?**

**Answer: NO - Absolutely Not**

**Evidence:**
```
SUSTAINABLE_IMPULSE:
  ├─ Requires: max_favorable_excursion (outcome data)
  ├─ Requires: max_adverse_excursion (outcome data)
  ├─ Requires: realized_pnl (outcome data)
  └─ Determination: AFTER trade exits

LIQUIDITY_EVENT:
  ├─ Requires: max_adverse_excursion (outcome data)
  ├─ Requires: max_favorable_excursion (outcome data)
  └─ Determination: AFTER trade exits

MEAN_REVERSION_TRAP:
  ├─ Requires: max_adverse_excursion (outcome data)
  ├─ Requires: max_favorable_excursion (outcome data)
  ├─ Requires: realized_pnl (outcome data)
  └─ Determination: AFTER trade exits

TEMPORARY_SPIKE:
  ├─ Requires: realized_pnl (outcome data)
  ├─ Requires: max_favorable_excursion (outcome data)
  └─ Determination: AFTER trade exits
```

### What This Means

**The impulse quality classifications are RETROACTIVE analysis, not real-time filtering.**

They tell you:
- ✅ Which signals WERE true momentum (after the fact)
- ✅ Which signals WERE false (after the fact)
- ❌ Which signals ARE true momentum (in real-time)
- ❌ Which signals ARE false (in real-time)

**This is a fundamental limitation of the analysis.**

---

## SECTION 5: TIMELINE OF DETERMINABILITY

### When Can Each Classification Be Determined?

| Phase | Time | What Exists | What's Missing | Can Classify |
|---|---|---|---|---|
| **Signal Generated** | T=0 | strategy_name, acceleration scores | MFE/MAE, PnL | ❌ NO |
| **Order Executes** | T=30s | entry_price, signal metrics | MFE/MAE finalized, PnL | ❌ NO |
| **Trade In Progress** | T=5min | growing MFE/MAE, unrealized PnL | Final PnL, final MFE/MAE | ⚠️ PARTIAL |
| **Trade Exits** | T=300min | realized_pnl, final MFE/MAE | — | ✅ YES |

**At Signal Time (T=0):**
- Cannot determine impulse quality
- Classification impossible with outcome-dependent metrics

**During Trade (T=5-300min):**
- Could make PARTIAL estimates based on accumulating MFE/MAE
- But final classification requires trade exit

**At Trade Exit:**
- All data available
- Classification definitive and retroactive

---

## SECTION 6: DATA LOOKUP DEPENDENCY

### Which Inputs Are "Looking Backward"?

**Outcome-Dependent Inputs (Retroactive):**
```
✅ max_favorable_excursion
   └─ Definition: Highest price reached AFTER entry
   └─ Availability: Only after trade path is complete
   └─ Can determine at signal? NO
   └─ Can determine at exit? YES

✅ max_adverse_excursion
   └─ Definition: Lowest price reached AFTER entry
   └─ Availability: Only after trade path is complete
   └─ Can determine at signal? NO
   └─ Can determine at exit? YES

✅ realized_pnl
   └─ Definition: Actual P&L after exit
   └─ Availability: Only after exit price known
   └─ Can determine at signal? NO
   └─ Can determine at exit? YES
```

**All three classification inputs are outcome-dependent.**

---

## SECTION 7: WHAT COULD BE DETERMINED AT SIGNAL TIME

### Available at Signal Generation

```
At T=0 (Signal Time):
  ├─ Volume acceleration score
  ├─ Momentum acceleration score
  ├─ Confidence score (NULL for 98%)
  ├─ Trend30m
  ├─ PCR ratio
  └─ Current market state
```

### Possible Real-Time Classifications (If Implemented)

If NSE_SPIKE wanted to classify signal quality at signal time, it could use:

**Before Entry:**
```
1. Volume acceleration pattern
   ├─ Is volume spike continuing to build?
   ├─ Or is it already peaked?
   └─ Requires multi-minute lookback

2. Momentum acceleration pattern
   ├─ Is momentum still accelerating?
   ├─ Or is acceleration slowing?
   └─ Requires comparison of current vs prior candles

3. Price structure
   ├─ Is move orderly (true impulse)?
   ├─ Or is it chaotic (liquidity event)?
   └─ Requires candle structure analysis

4. Market regime
   ├─ Is trend established?
   ├─ Or is market rangebound?
   └─ Requires regime detection
```

**During Entry:**
```
1. First 10 seconds of trade
   ├─ Is price moving in intended direction?
   ├─ Or immediately reversing (liquidity event)?
   └─ Could detect within first second

2. Initial volume confirmation
   ├─ Does volume sustain after entry?
   ├─ Or does it collapse?
   └─ Visible within 10-20 seconds
```

**But NSE_SPIKE doesn't do any of this.**

---

## SECTION 8: METHODOLOGY SUMMARY

### Classification Validity Assessment

| Aspect | Assessment |
|--------|---|
| **Uses outcome data?** | ✅ YES - MFE, MAE, realized_pnl |
| **Looks backward?** | ✅ YES - Retroactive classification |
| **Can classify at signal time?** | ❌ NO - All inputs are post-outcome |
| **Can classify at entry time?** | ❌ NO - No outcome data yet |
| **Can classify during trade?** | ⚠️ PARTIAL - Accumulating data |
| **Can classify at exit time?** | ✅ YES - All data finalized |
| **Is this predictive?** | ❌ NO - This is diagnostic |
| **Is this filtered in real-time?** | ❌ NO - No filtering mechanism |

---

## SECTION 9: IMPLICATIONS FOR NSE_SPIKE

### What This Validation Proves

**The impulse quality analysis reveals:**
1. NSE_SPIKE generates 792 signals
2. Only 35.86% (284) turn out to be true impulses (in retrospect)
3. 64.14% (508) turn out to be false signals (in retrospect)

**But here's the critical issue:**

NSE_SPIKE has **NO MECHANISM** to filter these at signal time.

It sends orders on:
- TRUE impulses (35.86%) ✅
- FALSE signals (64.14%) ❌

**Without knowing which is which.**

### Real-Time Problem

When NSE_SPIKE generates a signal, it doesn't know:
- Will this be a sustainable impulse? (Unknown until exit)
- Is this a liquidity event? (Unknown until price reverts)
- Is this a reversion trap? (Unknown until trade completes)
- Is this noise? (Unknown until MFE/MAE finalizes)

**The system enters blind.**

---

## SECTION 10: FORWARD-LOOKING CLASSIFICATION (Hypothetical)

### IF NSE_SPIKE Could Classify at Signal Time

To create forward-looking (predictive) impulse quality at signal time, it would need:

**Real-Time Metrics:**
1. **Acceleration trajectory** - Is volume/momentum still accelerating or flattening?
2. **Price structure quality** - Are candles ordered or chaotic?
3. **Volume duration** - Has the volume spike sustained for multiple candles?
4. **Trend confirmation** - Is this in line with established trend?
5. **Volatility regime** - Is move normal or extreme?

**These could be measured at signal time**, but NSE_SPIKE doesn't compute any of them for filtering.

---

## SECTION 11: CONCLUSION

### Classification Validity Verdict

**Can NSE_SPIKE impulse quality classifications be determined at signal time?**

### Answer: NO

**Reasoning:**
- All three inputs are **outcome-dependent**
- max_favorable_excursion requires knowing highest price reached AFTER entry
- max_adverse_excursion requires knowing lowest price reached AFTER entry
- realized_pnl requires knowing exit price and time
- These values only exist AFTER the trade completes

**This is a retroactive classification of completed trades, not a real-time prediction of signal quality.**

### Practical Implication

**NSE_SPIKE cannot determine if a signal is true momentum or false at the moment it generates the signal.**

It generates orders on all signals, then (days later) analysis reveals:
- 35.86% were actually good
- 64.14% were actually bad

**But by then, the losses are realized.**

---

## SECTION 12: METHODOLOGY TRANSPARENCY

### What This Analysis Did

**Correct description:**
"Post-trade impulse quality analysis"
- Takes completed trades
- Measures final MFE/MAE
- Measures realized PnL
- Classifies retroactively
- Shows which signals were true vs false in hindsight

**Incorrect claims it could do:**
❌ Predict which signals will be true or false in real-time
❌ Filter signals before they're generated
❌ Improve NSE_SPIKE entry decisions (would require real-time versions)
❌ Enable real-time risk management

### Value of This Analysis

**What it reveals:**
✅ NSE_SPIKE cannot distinguish signal types
✅ 64% of signals are objectively false (measured retroactively)
✅ True signals exist but are hidden in the noise
✅ System would need real-time filtering to improve

**What it doesn't prove:**
❌ Real-time filtering of these types is possible with NSE_SPIKE's metrics
❌ The 35.86% / 64.14% split is predictable in advance
❌ NSE_SPIKE could identify true impulses before generating signals

---

**NSE_SPIKE_CLASSIFICATION_VALIDATION COMPLETE**

**CRITICAL FINDING: All impulse quality classifications are RETROACTIVE - they require knowing the final MFE, MAE, and realized_pnl of completed trades. These values do not exist at signal generation time. Therefore, NSE_SPIKE cannot determine if a signal is true momentum or false at the moment it enters the market. The 35.86% true / 64.14% false split is a retrospective diagnosis, not a real-time prediction. The strategy enters blind, then analysis reveals the false signals weeks later.**

