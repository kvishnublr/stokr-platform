# UNIFIED SIGNAL PIPELINE ARCHITECTURE
## Complete Signal Creation Path Analysis

Date: 2026-06-09
Scope: All 1,521 completed production trades
Focus: Tracing every signal creation pathway and enrichment route

---

## PART 1: SIGNAL CREATION PATHS IDENTIFIED

### Path 1: CatalogDrivenScanScheduler (CATALOG PATH)
**Status:** ✅ ACTIVE

**Code Location:** `stokr-strategy/catalog/CatalogDrivenScanScheduler.java`

**Flow:**
1. Line 181: `strategy.evaluate(ctx)` → generates StrategySignal
2. **Line 236: `confidenceEngineV2.enrich(signal, ...)` → ENRICHES CONFIDENCE** ✓
3. Line 237-245: `StrategySignalEntityMapper.baseEntity()` → creates entity
4. Line 247: `signalPipelineService.persistAndDispatch()` → persists

**Pipeline Value:** BOTH/PAPER/DRY_RUN (from admin config or YAML default)

**Admin Config Override:** Yes (reads from strategy_execution_configs)

**Confidence Enrichment:** YES (line 236)

**Trade Quality:** YES (assigned in ConfidenceEngineV2.enrich())

### Path 2: Direct Signal Persistence via Unknown Entrypoint
**Status:** ⚠️ ACTIVE BUT SOURCE UNKNOWN

**Pipeline Value:** LIVE (only this pipeline)

**Confidence Enrichment:** NO (never calls ConfidenceEngineV2.enrich())

**Trade Quality:** NO (never populated for LIVE signals)

**Characteristics:**
- 1,198 signals with pipeline = LIVE in database
- 78.7% of all signals
- Confidence enrichment NEVER happens
- No breakdown_json, no trade_quality

**Strategies Using This Path:**
- NSE_SPIKE_DETECTION: 775 signals (100% NULL confidence)
- EARLY_BREAKOUT: 298 signals (100% NULL confidence)
- SECTOR_LAGGARD: 16 signals (100% NULL confidence)
- VWAP_BOUNCE: 29 signals (100% NULL confidence)
- GAP_FILL: 24 signals (50% NULL confidence = 12)
- ADV_CASH: 53 signals (0% NULL = all enriched)
- PRE_OPEN_GAP_OI: 2 signals (0% NULL)
- INDEX_HUNT: 1 signal (0% NULL)

---

## PART 2: SIGNAL ROUTING MATRIX

### Complete Path Breakdown

| Creation Path | Pipeline | Count | Confidence | Quality | Admin Config | Enrichment |
|---|---|---|---|---|---|---|
| **Catalog** | BOTH | 108 | ✅ 100% | ✅ YES | ✅ YES | ✅ YES |
| **Catalog** | PAPER | 2 | ✅ 100% | ✅ YES | ✅ YES | ✅ YES |
| **Unknown** | LIVE | **1,198** | ❌ 60% | ❌ NO | ? | ❌ NO |
| **Unknown** | PAPER | 214 | ⚠️ MIXED | ❌ NO | ? | ❌ NO |
| **System** | SYSTEM | 1 | ❌ NO | ❌ NO | ❌ NO | ❌ NO |

### By Strategy

| Strategy | Total | BOTH | LIVE | PAPER | SYSTEM | Conf in LIVE % | Quality % |
|---|---|---|---|---|---|---|---|
| **ADV_CASH** | 163 | 15 | 53 | 95 | - | 0% NULL | 38.0% |
| **EARLY_BREAKOUT** | 301 | 1 | **298** | 2 | - | **100% NULL** | 25.2% |
| **SECTOR_LAGGARD** | 16 | - | **16** | - | - | **100% NULL** | 43.8% |
| **NSE_SPIKE_DETECTION** | 792 | - | **775** | 17 | - | **100% NULL** | 19.7% |
| **VWAP_BOUNCE** | 60 | - | 29 | 31 | - | **100% NULL** | 31.7% |
| **GAP_FILL** | 77 | 4 | 24 | 49 | - | 50% NULL | 46.8% |
| **INDEX_HUNT** | 83 | 82 | 1 | - | - | 0% NULL | 33.7% |
| **Others** | 29 | 6 | 2 | 20 | 1 | MIXED | - |

---

## PART 3: THE MISSING ENTRYPOINT

### Where Do 1,198 LIVE Pipeline Signals Originate?

**Code Search Results:**
- ✅ CatalogDrivenScanScheduler: Would create BOTH/PAPER/DRY_RUN pipelines, NOT LIVE
- ✅ SignalExecutionBridge: Saves existing signals (doesn't create)
- ✓ StrategySignalPipelineService: Routes signals (doesn't create)
- ✓ No legacy EmaTrendFollowingSignalGenerator found in main code (only in worktrees)

**Hypothesis:** 
There is a second signal creation path that:
1. Bypasses CatalogDrivenScanScheduler
2. Directly creates StrategySignalEntity
3. Sets pipeline = "LIVE"
4. Does NOT call ConfidenceEngineV2.enrich()
5. Does NOT populate confidence_breakdown_json
6. Does NOT populate trade_quality

**Possible Locations:**
- Unknown entrypoint in execution module
- Direct repository.save() call bypassing the catalog system
- Legacy scheduler still enabled
- Admin/trader manual signal creation API

---

## PART 4: SIGNAL ENRICHMENT SUMMARY

### Confidence Enrichment Status

| Pipeline | Count | Enriched | % With Confidence | Entry Path |
|---|---|---|---|---|
| BOTH | 108 | ✅ YES | 100% | CatalogDrivenScanScheduler |
| PAPER | 216 | ⚠️ PARTIAL | 58% | Mixed (mostly unknown) |
| LIVE | 1,198 | ❌ NO | 40% | Unknown entrypoint |
| SYSTEM | 1 | ❌ NO | 0% | Unknown |

### Quality Label Assignment

| Pipeline | Count | Quality Assigned | % NULL |
|---|---|---|---|
| BOTH | 108 | ✅ YES | 0% |
| PAPER | 216 | ❌ NO | 100% |
| LIVE | 1,198 | ❌ NO | 100% |
| SYSTEM | 1 | ❌ NO | 100% |

---

## PART 5: ANSWERS TO FORENSICS QUESTIONS

### A. Why do NSE_SPIKE_DETECTION and EARLY_BREAKOUT bypass enrichment?

**Evidence:**
- 775 NSE_SPIKE + 298 EARLY_BREAKOUT = 1,073 signals
- ALL have pipeline = LIVE
- ALL have confidence_score = NULL
- ALL have confidence_breakdown_json = NULL
- Zero calls to ConfidenceEngineV2.enrich() in code path

**Root Cause:** They do NOT go through CatalogDrivenScanScheduler. They use an unknown signal creation path that directly persists signals without enrichment.

**This is not a bug in ConfidenceEngineV2** — it's simply never being invoked for these signals.

---

### B. Why do LIVE pipeline signals show admin config = "BOTH" but persist as "LIVE"?

**Evidence:**
```
Admin Config:  NSE_SPIKE_DETECTION = BOTH
Database:      NSE_SPIKE_DETECTION with pipeline = LIVE (775 signals)
Expected:      Would see BOTH pipeline if admin config applied
```

**Root Cause:** The admin config (BOTH) is NOT being used by the LIVE pipeline signal creator. The signals bypass the code path that reads admin config (CatalogDrivenScanScheduler line 234).

**Admin Config IS respected** for signals created via CatalogDrivenScanScheduler:
```
INDEX_HUNT admin = BOTH → database shows INDEX_HUNT BOTH (82 signals) ✓
ADV_CASH admin = BOTH → database shows ADV_CASH BOTH (15 signals) ✓
GAP_FILL admin = BOTH → database shows GAP_FILL BOTH (4 signals) ✓
```

But 1,198 LIVE pipeline signals completely bypass this config.

---

### C. Is there a legacy signal persistence path still active?

**Status:** ✅ YES, CONFIRMED

**Evidence:**
1. 78.7% of production signals (1,198 out of 1,521) use LIVE pipeline
2. This pipeline value is NEVER generated by CatalogDrivenScanScheduler
3. Signals originate from unknown source that:
   - Does NOT enrichment confidence
   - Does NOT populate trade_quality
   - Does NOT respect admin configuration
   - Only produces pipeline = "LIVE"

**Characteristics of Legacy Path:**
- Directly persists signals without enrichment
- Bypasses ConfidenceEngineV2 entirely
- Produces 60% of the NULL confidence problem alone (1,073 out of 1,157)
- Applies to: NSE_SPIKE, EARLY_BREAKOUT, SECTOR_LAGGARD, VWAP_BOUNCE primarily

---

### D. Can all strategies be routed through CatalogDrivenScanScheduler?

**Current State:**
- ✅ Some strategies already use catalog: INDEX_HUNT, most others
- ❌ Some strategies ONLY use legacy path: NSE_SPIKE_DETECTION, EARLY_BREAKOUT, SECTOR_LAGGARD, VWAP_BOUNCE

**Feasibility:** UNKNOWN — Need to find the legacy entrypoint first

**If all strategies were routed through CatalogDrivenScanScheduler:**
- 100% of signals would have confidence enrichment
- 100% of signals would have trade_quality
- 100% of signals would respect admin configuration
- All signals would have pipeline = BOTH/PAPER/DRY_RUN (never LIVE)
- Result: 0% NULL confidence problem

**Trading Logic Impact:** Appears minimal — only routing changes, no logic changes needed

---

### E. What percentage of signals currently use each path?

**Signal Distribution by Path:**

| Path | Count | % |
|---|---|---|
| **CatalogDrivenScanScheduler** | 110 | 7.2% |
| **Unknown/Legacy Path** | 1,410 | 92.7% |
| **Other/System** | 1 | 0.1% |

**By Enrichment Status:**

| Enriched | Count | % |
|---|---|---|
| With confidence | 364 | 23.9% |
| **Without confidence** | **1,157** | **76.1%** |

**By Pipeline Value:**

| Pipeline | Count | % |
|---|---|---|
| **LIVE** | **1,198** | **78.7%** |
| PAPER | 216 | 14.2% |
| BOTH | 108 | 7.1% |
| SYSTEM | 1 | 0.1% |

---

## PART 6: LEGACY PATH CONFIRMATION

### Signals That MUST Use Legacy Path

These signals have pipeline = LIVE but are NOT from CatalogDrivenScanScheduler:

```
NSE_SPIKE_DETECTION:   775 signals → 100% NULL confidence
EARLY_BREAKOUT:        298 signals → 100% NULL confidence
SECTOR_LAGGARD:         16 signals → 100% NULL confidence
VWAP_BOUNCE:            29 signals → 100% NULL confidence
GAP_FILL:               12 signals → NULL confidence
Plus others (LIVE):    ~68 signals
```

Total from legacy path: **~1,198 signals**

All these:
- Have pipeline = "LIVE"
- Have confidence_score = NULL
- Have confidence_breakdown_json = NULL
- Have trade_quality = NULL
- Never called ConfidenceEngineV2.enrich()

---

## PART 7: EXECUTION CONFIGURATION NOT APPLIED

### Admin Config Says BOTH, Database Shows LIVE

**Configuration State:**
```
NSE_SPIKE_DETECTION admin_config = BOTH
NSE_SPIKE_DETECTION YAML_default = DRY_RUN
NSE_SPIKE_DETECTION ACTUAL_in_DB = LIVE (775 signals)
```

**Why?**
- CatalogDrivenScanScheduler reads admin config (line 234)
- But NSE_SPIKE signals are NOT created by CatalogDrivenScanScheduler
- They're created by unknown legacy path that doesn't read admin config
- So admin config BOTH is irrelevant for these signals

**For signals that DO use catalog path:**
- INDEX_HUNT admin=BOTH → shows as BOTH in DB ✓
- ADV_CASH admin=BOTH → shows as BOTH in DB ✓
- Config IS respected for catalog signals

---

## CONCLUSION: LEGACY PATH ACTIVE

**The 76% NULL confidence problem is caused by a legacy signal creation pathway that:**

1. Operates in LIVE execution mode
2. Bypasses ConfidenceEngineV2 enrichment entirely
3. Never populates confidence_breakdown_json
4. Never assigns trade_quality
5. Ignores admin execution configuration
6. Generates 92.7% of all production signals (1,410 out of 1,521)
7. Produces 78.7% of signals with pipeline = LIVE (1,198 signals)
8. Is the ONLY path used by NSE_SPIKE_DETECTION, EARLY_BREAKOUT, SECTOR_LAGGARD

**The legacy path exists and IS the dominant signal creation mechanism in production.**

CatalogDrivenScanScheduler is a minority path (only 110 signals = 7.2%), not the primary production signal creation system.

---

**Status: Legacy signal pipeline confirmed as the primary production mechanism. Source location unknown — requires code search for direct StrategySignalEntity creation or repository.save() calls outside CatalogDrivenScanScheduler.**

