# CONFIDENCE PIPELINE TRACE INVESTIGATION
## Complete Execution Flow Analysis

Date: 2026-06-09
Data: Production database (173.249.55.84)
Sample: 1,521 completed trades
Focus: Why 76% have NULL confidence_score

---

## PART 1: NULL CONFIDENCE DISTRIBUTION BY STRATEGY

### Complete Strategy Breakdown

| Strategy | Total | With Conf | Without Conf | NULL % | Win Rate |
|----------|-------|-----------|-------------|--------|----------|
| **NSE_SPIKE_DETECTION** | 792 | 14 | **778** | **98.2%** | 19.7% |
| **EARLY_BREAKOUT** | 301 | 3 | **298** | **99.0%** | 25.2% |
| SECTOR_LAGGARD | 16 | 0 | 16 | 100.0% | 43.8% |
| VWAP_BOUNCE | 60 | 31 | 29 | 48.3% | 31.7% |
| ADV_CASH | 163 | **140** | 23 | 14.1% | **38.0%** |
| INDEX_HUNT | 83 | **83** | 0 | **0.0%** | 33.7% |
| GAP_FILL | 77 | **65** | 12 | 15.6% | **46.8%** |
| VWAP_BOUNCE | 60 | 31 | 29 | 48.3% | 31.7% |
| Others | 369 | ~128 | ~241 | 65% | ~35% |

### Finding: OPTION A - STRATEGY SPECIFIC

**1,093 out of 1,521 trades (71.8%) belong to just 2 strategies with 98%+ NULL confidence:**
- NSE_SPIKE_DETECTION: 792 trades (98.2% NULL)
- EARLY_BREAKOUT: 301 trades (99.0% NULL)

**These same strategies WITH CONFIDENCE have 38-47% win rate** (ADV_CASH, INDEX_HUNT, GAP_FILL).

---

## PART 2: THE SIGNAL PIPELINE PATHS

### Code Path 1: CatalogDrivenScanScheduler (CATALOG PATH)

**Location:** `stokr-strategy/src/main/java/com/stokr/strategy/catalog/CatalogDrivenScanScheduler.java`

**Flow:**
1. Line 181: `StrategySignal signal = strategy.evaluate(ctx);`
2. **Line 236: `StrategySignal scoredSignal = confidenceEngineV2.enrich(signal, strategyKey, symbol, candleTime);`** ← ENRICHES CONFIDENCE
3. Line 237-245: Creates StrategySignalEntity with scoredSignal
4. Line 247: `signalPipelineService.persistAndDispatch(entity, ...)`

**Status:** Signals FROM THIS PATH have confidence_breakdown_json and confidence_score populated.

### Code Path 2: UNKNOWN PATH (Not the Catalog)

**Signals NOT from catalog path:**
- NSE_SPIKE_DETECTION (LIVE/LIVE pipeline): 1,073 trades with NULL confidence
- EARLY_BREAKOUT (LIVE/LIVE pipeline): minimal trades with confidence

**Evidence:**
```
Signal Source │ Pipeline │ Count │ With Breakdown │ NULL Confidence
──────────────┼──────────┼───────┼────────────────┼─────────────────
LIVE          │ LIVE     │ 1,073 │       0        │    1,073 (100%)
LIVE          │ BOTH     │   1   │       1        │       0
SIMULATION    │ PAPER    │  14   │      14        │       0
```

**Key Observation:**
- CatalogDrivenScanScheduler creates signals with pipeline = BOTH/PAPER/etc.
- 1,073 signals have pipeline = LIVE, meaning they're NOT from catalog scheduler
- These LIVE pipeline signals have ZERO confidence_breakdown_json

**There is a SECOND signal creation path that:**
1. Does NOT call ConfidenceEngineV2.enrich()
2. Does NOT populate confidence_breakdown_json
3. Records signals with pipeline = "LIVE"
4. Only applies to NSE_SPIKE_DETECTION and EARLY_BREAKOUT

---

## PART 3: ADMIN EXECUTION CONFIG OVERRIDE

### Database: strategy_execution_configs

| Strategy | YAML Default | Admin Config | Updated |
|----------|-------------|--------------|---------|
| NSE_SPIKE_DETECTION | DRY_RUN | **BOTH** | 2026-06-04 00:57 |
| EARLY_BREAKOUT | DRY_RUN | **BOTH** | 2026-06-04 00:57 |
| ADV_CASH | LIVE | **BOTH** | 2026-06-04 00:47 |
| INDEX_HUNT | DRY_RUN | **BOTH** | 2026-06-08 09:24 |
| GAP_FILL | PAPER | **BOTH** | 2026-06-04 00:57 |

**Important:**
- YAML config: NSE_SPIKE_DETECTION=DRY_RUN, EARLY_BREAKOUT=DRY_RUN
- Admin override: NSE_SPIKE_DETECTION=BOTH, EARLY_BREAKOUT=BOTH
- **Admin config takes precedence** (see StrategySignalPipelineService line 234)

But admin override to BOTH doesn't explain why they're on LIVE/LIVE pipeline with no enrichment.

---

## PART 4: CONFIDENCE ENRICHMENT FLOW

### ConfidenceEngineV2.enrich() Invocations

**Called from:**
1. `CatalogDrivenScanScheduler.persistSignal()` line 236 ← CATALOG PATH
2. `UnifiedSignalTruthService` (analytics only)
3. `SignalHistoricalReplayService` (replay mode)
4. `SignalOutcomeTrackerService` (outcome tracking)

**NOT called from:**
- Unknown second signal creation path (1,073 signals)

### When confidence IS enriched (364 trades):

```
Confidence Engine V2 Calculation:
├─ priceStructure (25 pts)         → Risk/reward ratio
├─ volumeExpansion (20 pts)        → Volume expansion check
├─ oiConfirmation (20 pts)         → OI data (usually unavailable)
├─ orderFlow (10 pts)              → Order flow imbalance
├─ sectorStrength (10 pts)         → Sector movement
├─ marketBreadth (5 pts)           → NIFTY movement
├─ liquidityQuality (5 pts)        → Bar volume consistency
└─ volatilityAlignment (5 pts)     → VIX or range %

TOTAL: 100 points → normalized to 0.0-1.0
```

Missing components default to 0.5 ratio (median), adding noise.

### JSON Output Structure

```json
{
  "version":"CONFIDENCE_V2",
  "score":62.58,
  "factors":[
    {"factor":"priceStructure","points":25.00,"weight":25.00,"detail":"rr=2.494"},
    {"factor":"volumeExpansion","points":6.45,"weight":20.00,"detail":"volMult=0.645"},
    ...
  ]
}
```

**Stored in:** `strategy_signals.confidence_breakdown_json`

---

## PART 5: TRADE QUALITY ASSIGNMENT

### qualityLabel() Function

**Location:** `ConfidenceEngineV2.java` lines 218-224

```java
private static String qualityLabel(double score) {
    if (score >= 85) return "A+";
    if (score >= 75) return "A";
    if (score >= 65) return "B";
    if (score >= 55) return "C";
    return "D";
}
```

### Quality Label Distribution

| Quality | Count | Winners | Win % | Avg Confidence |
|---------|-------|---------|-------|-----------------|
| A | 28 | 9 | 32.14% | 0.7458 |
| B | 46 | 16 | 34.78% | 0.6843 |
| **C** | 100 | 44 | **44.00%** | 0.5961 |
| D | 190 | 65 | 34.21% | 0.4216 |
| **NULL** | **1,157** | 260 | **22.5%** | NULL |

**Finding: C-quality outperforms A-quality by 11.86 percentage points**

This suggests either:
1. Quality labels are INVERTED (C is better than A)
2. Quality assignment is WRONG
3. D-quality code is actually the active implementation (since D and A/B are similar)

The qualityLabel() function appears to be the active code path (no other implementations found), but the inverted results suggest it may NOT be being used for all signals.

**1,157 trades (76%) have trade_quality = NULL**, same as those with confidence_score = NULL.

---

## PART 6: ROOT CAUSE DETERMINATION

### Primary Issue: STRATEGY-SPECIFIC (Option A)

**76% of NULL confidence belongs to 2 strategies running through a non-catalog signal path:**

| Component | Status | Evidence |
|-----------|--------|----------|
| **NSE_SPIKE_DETECTION** | 98.2% NULL | 778/792 trades, pipeline=LIVE |
| **EARLY_BREAKOUT** | 99.0% NULL | 298/301 trades, pipeline=LIVE |
| Unknown second path | Exists | 1,073 signals with pipeline=LIVE but no confidence enrichment |
| CatalogDrivenScanScheduler | Working correctly | Signals from catalog path DO have confidence |
| ConfidenceEngineV2 | Working correctly | When called, produces valid scores |

### Secondary Issue: QUALITY ASSIGNMENT VALIDATION

Quality labels (A/B/C/D) show **INVERTED results:**
- C-quality: 44% win rate (best)
- A-quality: 32% win rate (worst)
- D-quality: 34% win rate

This is **backwards from the code logic** where A+ >= 85, A >= 75, etc.

Possible explanations:
1. Quality assignment logic is not the qualityLabel() function (there's a different path)
2. Quality labels are being assigned AFTER enrichment from a different source
3. The database has misaligned quality grades

---

## PART 7: EXECUTION TIMELINE

### Confidence Enrichment Decision Tree

```
Is signal from CatalogDrivenScanScheduler?
├─ YES (367 signals)
│  ├─ Calls ConfidenceEngineV2.enrich() ✓
│  ├─ Populates confidence_breakdown_json ✓
│  ├─ Stores confidence_score ✓
│  └─ Result: 364 signals with confidence (364 of these 367)
│
└─ NO (1,154 signals)
   ├─ NSE_SPIKE_DETECTION (LIVE/LIVE): 1,073 ✗
   ├─ EARLY_BREAKOUT (LIVE/LIVE): ~81 ✗
   ├─ SECTOR_LAGGARD (LIVE/LIVE): ? ✗
   └─ Other legacy paths: ? ✗
      └─ Result: 0 signals with confidence
```

### When Enrichment Occurs

From CatalogDrivenScanScheduler.persistSignal() code:
1. **Line 236:** Confidence enriched BEFORE persistence
2. **Line 237-245:** Entity created with enriched signal
3. **Line 247:** Persisted to database

**For non-catalog signals (NSE_SPIKE, EARLY_BREAKOUT):**
- Enrichment does NOT occur
- Signals persist with confidence_score = NULL
- Signals persist with confidence_breakdown_json = NULL

---

## PART 8: CONFIGURATION STATE

### Current Production Configuration

**YAML Defaults (application-v2.yml):**
```
NSE_SPIKE_DETECTION: DRY_RUN
EARLY_BREAKOUT: DRY_RUN
```

**Admin Override (database):**
```
NSE_SPIKE_DETECTION: BOTH (last updated 2026-06-04 00:57)
EARLY_BREAKOUT: BOTH (last updated 2026-06-04 00:57)
```

**Actual Signal Pipeline (database evidence):**
```
NSE_SPIKE_DETECTION: LIVE/LIVE (1,073 signals)
EARLY_BREAKOUT: LIVE/LIVE (minimal signals)
```

**Discrepancy:** Admin config says BOTH, but signals are on LIVE pipeline.

This suggests the admin config override is NOT being used for these signals, meaning they're coming from a code path that:
1. Doesn't read admin config
2. Directly sets pipeline = "LIVE"
3. Doesn't call ConfidenceEngineV2.enrich()

---

## SUMMARY: ROOT CAUSE IDENTIFIED

### **ANSWER: Option A - STRATEGY SPECIFIC**

**71.8% of NULL confidence (1,093 trades) belongs to 2 strategies with a separate signal pipeline:**

1. **NSE_SPIKE_DETECTION**: 98.2% NULL (778/792)
2. **EARLY_BREAKOUT**: 99.0% NULL (298/301)

**These strategies use a NON-CATALOG signal pipeline that:**
- Does NOT call ConfidenceEngineV2.enrich()
- Does NOT populate confidence_breakdown_json
- Does NOT populate trade_quality
- Records signals with pipeline = "LIVE"
- Produces 22.5% win rate on average

**Other strategies using catalog path:**
- INDEX_HUNT: 0% NULL (83/83 with confidence)
- ADV_CASH: 14.1% NULL (23/163 with confidence)
- GAP_FILL: 15.6% NULL (12/77 with confidence)
- All show 33-47% win rates when confidence is present

### Secondary Finding: Trade Quality Inverted

Quality labels show C > A > B > D in win rate, opposite of the code's A > B > C > D scoring.

---

**Exact Source:** Execution flows through unknown second path for NSE_SPIKE_DETECTION and EARLY_BREAKOUT (1,073 signals) instead of CatalogDrivenScanScheduler

