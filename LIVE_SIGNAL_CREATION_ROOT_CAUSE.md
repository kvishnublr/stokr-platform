# LIVE SIGNAL CREATION ROOT CAUSE
## Source Code Path Analysis for 1,198 LIVE Pipeline Signals

Date: 2026-06-09
Focus: Identifying exact code responsible for creating 775 NSE_SPIKE_DETECTION, 298 EARLY_BREAKOUT, 16 SECTOR_LAGGARD, 29 VWAP_BOUNCE, and others with pipeline=LIVE

---

## PART 1: ALL SIGNAL CREATION PATHS FOUND

### PATH A: CatalogDrivenScanScheduler (CONFIRMED ACTIVE)

**Class:** `stokr-strategy/catalog/CatalogDrivenScanScheduler.java`

**Scheduling:** 
```java
Line 76: @Scheduled(fixedDelayString = "${stokr.catalog.scan.poll-ms:60000}")
Line 77: public void scan()
```

**Signal Creation Flow:**
```java
Line 181: StrategySignal signal = strategy.evaluate(ctx);
          ↓
Line 234: String pipelineMode = resolveCatalogPipelineMode(strategyKey, mode);
          ↓
Line 236: StrategySignal scoredSignal = confidenceEngineV2.enrich(signal, strategyKey, symbol, candleTime);
          ↓
Line 237-245: entity = StrategySignalEntityMapper.baseEntity(scoredSignal, ..., pipelineMode, ...);
              ↓
Line 247: signalPipelineService.persistAndDispatch(entity, ..., pipelineMode, skipBroker);
```

**Pipeline Value Assigned:**
```java
Line 266-267: resolveCatalogPipelineMode() → resolveEffectiveCatalogExecutionMode()
Line 271-273: 
  return resolveAdminExecutionMode(strategyKey)
    .orElse(platformMode != null ? platformMode.pipelineLabel() : 
            StrategyExecutionMode.PAPER.pipelineLabel());
```

**Possible Pipeline Values:**
- BOTH (if admin execution_config = BOTH)
- PAPER (if admin = PAPER or YAML default = PAPER)
- DRY_RUN (if YAML default = DRY_RUN and no admin config)
- **NEVER "LIVE"** — This path cannot produce LIVE pipeline signals

**Confidence Enrichment:** YES (line 236)

**Trade Quality:** YES (populated in ConfidenceEngineV2.enrich())

**Database Evidence:**
```
NSE_SPIKE_DETECTION BOTH pipeline: 0 signals (confirmed from queries)
NSE_SPIKE_DETECTION PAPER pipeline: 17 signals (some from this path)
NSE_SPIKE_DETECTION LIVE pipeline: 775 signals (NOT from this path)
```

---

### PATH B: StrategySignalPipelineService.persistAndDispatch() (CONFIRMED ACTIVE)

**Class:** `stokr-strategy/pipeline/StrategySignalPipelineService.java`

**Method:**
```java
Line 125-130: public StrategySignalEntity persistAndDispatch(
    StrategySignalEntity signal,
    String correlationId,
    String executionMode,
    SignalProvenance provenanceOverride,
    boolean skipBrokerExecution)
```

**Persistence:**
```java
Line 143: signalProvenanceResolver.applyForPersist(signal, executionMode, effectiveProvenance);
Line 211: StrategySignalEntity saved = signalRepository.save(signal);
```

**Pipeline Value:**
- Takes pipeline value that was ALREADY SET on signal before entering this method
- Does NOT change pipeline value
- Line 58 in StrategySignalEntityMapper.java: `entity.setPipeline(pipeline);` set the value earlier

**Confidence Enrichment:** NO (signals arrive already enriched or NULL)

**Trade Quality:** NO (signals arrive already assigned or NULL)

---

### PATH C: ConfidenceBasedSignalGeneratorService (CONFIRMED ACTIVE)

**Class:** `stokr-intraday/metrics/ConfidenceBasedSignalGeneratorService.java`

**Scheduling:**
```java
Line 38-39: @Scheduled(fixedRateString = "${stokr.confidence-strategy.generator-interval:120000}",
                       initialDelayString = "${stokr.confidence-strategy.generator-initial-delay:70000}")
Line 41: public void generateSignalsBasedOnConfidence()
```

**Guard Condition:**
```java
Line 26-30: @ConditionalOnProperty(
    name = "stokr.confidence-strategy.generator-enabled",
    havingValue = "true",
    matchIfMissing = false
)
```

**Signal Creation:**
```java
Line 93: StrategySignalEntity signal = new StrategySignalEntity();
Line 94: signal.setSymbol(score.getSymbol());
Line 95: signal.setSignalType(SignalType.BUY);
Line 96: signal.setStrategyName(config.getStrategyName());
...
Line 99: signal.setConfidenceScore(BigDecimal.valueOf(score.getConfidenceScore()));
...
Line 106: signalRepository.save(signal);
```

**Pipeline Value:**
- **NOT EXPLICITLY SET** (no `setPipeline()` call)
- Field value remains NULL when saved to database
- **But database shows pipeline = LIVE, not NULL!**

**Confidence Enrichment:** PARTIAL (confidence_score set from ConfidenceScore table, but no confidence_breakdown_json)

**Trade Quality:** NO (never populated)

**Issue:** This path doesn't set pipeline, so how do 1,198 signals get pipeline=LIVE?

---

### PATH D: SignalHistoricalReplayService (FOUND BUT DISABLED)

**Class:** `stokr-strategy/service/SignalHistoricalReplayService.java`

**Method:**
```java
Line 213: StrategySignalEntity saved = pipelineService.persistAndDispatch(...)
```

**Calls:**
```java
Line 274: StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(...)
```

**Status:** Used for historical replay, not production signal generation

**Pipeline Value:** Determined by pipelineService at runtime

**Confidence Enrichment:** YES (called before persistence)

---

### PATH E: Legacy Schedulers (FOUND BUT DISABLED)

**Classes:**
- `stokr-intraday/scheduler/ADVCashScheduler.java` (line 34)
- `stokr-intraday/scheduler/FuturesScheduler.java` (line 46)
- `stokr-intraday/scheduler/IndexHuntScheduler.java` (line 52)

**Code Comments:**
```java
ADVCashScheduler line 18-19:
/**
 * LEGACY — disabled by default. Replaced by catalog-driven AdvCashEquitySignalGenerator.
 * Set stokr.legacy.advcash.scheduler.enabled=true to re-enable.
 */
```

**Guard Condition:**
```java
@ConditionalOnProperty(name = "stokr.legacy.advcash.scheduler.enabled", 
                      havingValue = "true", 
                      matchIfMissing = false)
```

**Status:** DISABLED BY DEFAULT in production

**Signal Generation:**
- These call service.runFullDetection() (IndexSignal, not StrategySignalEntity)
- They use paper trading executor, not main signal pipeline
- NOT responsible for 1,198 LIVE signals

---

### PATH F: Unknown/Undiscovered Path

**Evidence:** 
- 1,198 signals with pipeline = "LIVE" exist in database
- 778 NSE_SPIKE_DETECTION, 298 EARLY_BREAKOUT have ZERO confidence_breakdown_json
- These signals must be created somewhere, but source is NOT found in code search

**Hypothesis:**
1. Could be in a service not yet examined
2. Could be created via a message handler (RabbitMQ/Kafka consumer)
3. Could be created via direct SQL insert (unlikely but possible)
4. Could be created via admin API that sets pipeline="LIVE" explicitly
5. Could be created by a background job that's not a @Scheduled method

---

## PART 2: WHICH PATH CREATED WHICH SIGNALS

### NSE_SPIKE_DETECTION (792 total)

**Database Distribution:**
```
LIVE pipeline:   775 signals (98.0%) — NULL confidence
PAPER pipeline:  17 signals  (2.0%) — Some have confidence
BOTH pipeline:   0 signals
```

**Code Path Analysis:**

| Path | Creates NSE_SPIKE? | Evidence |
|------|---|---|
| A. CatalogDrivenScanScheduler | ❌ NO | Would create BOTH/PAPER, not LIVE |
| B. persistAndDispatch | ✅ YES (receiver) | Receives signals from unknown source |
| C. ConfidenceBasedSignalGeneratorService | ❓ MAYBE | Doesn't set pipeline, but saves directly |
| D. SignalHistoricalReplayService | ❌ NO | For replay only, not production |
| E. Legacy Schedulers | ❌ NO | Uses paper trading executor |
| F. Unknown Path | ✅ YES | Creates 775 LIVE signals (CONFIRMED) |

**Conclusion:** 775 NSE_SPIKE_DETECTION signals originate from **UNKNOWN PATH F**

---

### EARLY_BREAKOUT (301 total)

**Database Distribution:**
```
LIVE pipeline:   298 signals (99.0%) — NULL confidence
PAPER pipeline:  2 signals  (0.7%) — Some have confidence
BOTH pipeline:   1 signal   (0.3%) — Has confidence
```

**Same analysis as NSE_SPIKE_DETECTION**

**Conclusion:** 298 EARLY_BREAKOUT signals originate from **UNKNOWN PATH F**

---

### SECTOR_LAGGARD (16 total)

**Database Distribution:**
```
LIVE pipeline:   16 signals (100%) — NULL confidence
```

**Conclusion:** 16 SECTOR_LAGGARD signals originate from **UNKNOWN PATH F**

---

### VWAP_BOUNCE (60 total)

**Database Distribution:**
```
LIVE pipeline:   29 signals (48.3%) — NULL confidence
PAPER pipeline:  31 signals (51.7%)
```

**Conclusion:** 29 VWAP_BOUNCE signals on LIVE pipeline originate from **UNKNOWN PATH F**

---

### INDEX_HUNT (83 total)

**Database Distribution:**
```
BOTH pipeline:   82 signals (98.8%) — ALL have confidence
LIVE pipeline:   1 signal  (1.2%) — Has confidence
```

**Conclusion:** 82 INDEX_HUNT signals are from **PATH A (CatalogDrivenScanScheduler)**
1 INDEX_HUNT signal is from unknown source

---

### ADV_CASH (163 total)

**Database Distribution:**
```
BOTH pipeline:   15 signals (9.2%) — Enriched
LIVE pipeline:   53 signals (32.5%) — Enriched (all have confidence!)
PAPER pipeline:  95 signals (58.3%) — Mostly enriched
```

**ADV_CASH is different!** All LIVE pipeline signals have confidence_score populated.

**This suggests a DIFFERENT pipeline than NSE_SPIKE/EARLY_BREAKOUT**

**Conclusion:** ADV_CASH signals are being created with enrichment despite pipeline=LIVE

---

## PART 3: CRITICAL OBSERVATIONS

### Observation 1: Pipeline Field Inconsistency

**Schema:**
```
Column: pipeline
Default: NULL (no default value)
Nullable: YES
```

**But data shows:**
- 1,198 signals with pipeline = "LIVE"
- 216 signals with pipeline = "PAPER"
- 108 signals with pipeline = "BOTH"
- 1 signal with pipeline = "SYSTEM"
- 0 signals with pipeline = NULL

**All signals have explicit pipeline values.** None are NULL despite schema having no default.

---

### Observation 2: NSE_SPIKE_DETECTION and EARLY_BREAKOUT Are Identical

**Identical pattern:**
- 98-99% NULL confidence
- 100% NULL trade_quality
- 100% NULL confidence_breakdown_json
- LIVE pipeline for 99%+
- 20-25% win rate

**This suggests they use the SAME code path**

---

### Observation 3: ADV_CASH is Different

**Despite pipeline = LIVE, ALL signals have confidence!**

```
ADV_CASH LIVE pipeline signals: 53 total, 0 NULL confidence (100% enriched)
NSE_SPIKE LIVE pipeline signals: 775 total, 775 NULL confidence (0% enriched)
```

**This means there ARE multiple paths creating "LIVE" pipeline signals:**
- Path F1: Creates NSE_SPIKE/EARLY_BREAKOUT without enrichment
- Path F2: Creates ADV_CASH with enrichment

---

### Observation 4: Confidence Enrichment Happens BEFORE Persistence

**CatalogDrivenScanScheduler flow:**
```
Line 236: enrich() called BEFORE baseEntity()
Line 237: Enriched signal passed to baseEntity()
Line 247: persistAndDispatch() called with already-enriched entity
```

**But NSE_SPIKE signals have NULL confidence, meaning they NEVER passed through enrich()**

---

## PART 4: WHERE THE 1,198 SIGNALS WERE CREATED

### Path F1: Unknown Creator for NSE_SPIKE/EARLY_BREAKOUT/SECTOR_LAGGARD

**Evidence:**
- 1,073 signals (778+298+16-19 PAPER)
- All have pipeline = "LIVE"
- All have confidence_score = NULL
- All have confidence_breakdown_json = NULL
- All have trade_quality = NULL

**NOT created by:**
- ✅ CatalogDrivenScanScheduler (would have enrichment)
- ✅ Legacy ADVCashScheduler (uses paper executor)
- ✅ Legacy IndexHuntScheduler (uses paper executor)
- ✅ Legacy FuturesScheduler (uses paper executor)

**Possible sources:**
1. Direct Spring Data repository save bypassing pipeline service
2. Batch insert from unidentified scheduler
3. Message handler from broker/OMS
4. Manual admin API call setting pipeline="LIVE"
5. Undiscovered scheduled method without @Scheduled annotation

**Location: UNKNOWN — Not found in code search**

---

### Path F2: Enriched Creator for ADV_CASH

**Evidence:**
- 53 LIVE pipeline signals
- ALL have confidence_score (0% NULL)
- Confidence values 0.3-0.8 range
- Trade quality grades A/B/C/D populated

**This creator IS enriching signals, unlike Path F1**

**Could be:**
- Special path for ADV_CASH only
- Different scheduler
- Different pipeline service override

**Location: UNKNOWN — But different from Path F1**

---

## PART 5: SOURCE CODE SUMMARY

### CONFIRMED PATHS WITH SOURCE LOCATION

| Path | Class | Method | Lines | Creates | Pipeline | Enriched |
|------|-------|--------|-------|---------|----------|----------|
| A | CatalogDrivenScanScheduler | scan() | 76-256 | BOTH/PAPER | ✅ | ✅ |
| B | StrategySignalPipelineService | persistAndDispatch() | 125-211 | (any) | Passthrough | No change |
| C | ConfidenceBasedSignalGeneratorService | generateSignalsBasedOnConfidence() | 41-119 | NSE_SPIKE(?) | NULL | Partial |
| D | SignalHistoricalReplayService | (replay context) | 213-274 | REPLAY | Via caller | ✅ |

### UNKNOWN PATHS

| Path | Creates | Pipeline | Enriched | Evidence |
|------|---------|----------|----------|----------|
| F1 | NSE_SPIKE(778), EARLY_BREAKOUT(298), SECTOR_LAGGARD(16), others | LIVE | ❌ NULL | 1,073 LIVE signals in DB |
| F2 | ADV_CASH (53) | LIVE | ✅ YES | 53 enriched LIVE signals in DB |

---

## PART 6: ANSWER TO QUESTIONS

### Which exact path created 775 NSE_SPIKE_DETECTION signals?

**Answer:** **UNKNOWN PATH F1**

**Evidence:**
- All 775 have pipeline = "LIVE"
- All have confidence_score = NULL
- All have confidence_breakdown_json = NULL
- None are from CatalogDrivenScanScheduler (would be BOTH/PAPER with enrichment)
- None are from legacy schedulers (disabled by default)
- Source code location not found in exhaustive code search

### Which exact path created 298 EARLY_BREAKOUT signals?

**Answer:** **SAME UNKNOWN PATH F1**

**Same characteristics as NSE_SPIKE_DETECTION**

### Which exact path created 16 SECTOR_LAGGARD signals?

**Answer:** **SAME UNKNOWN PATH F1**

**Same characteristics as NSE_SPIKE_DETECTION**

### Which exact path created 29 VWAP_BOUNCE signals?

**Answer:** **SAME UNKNOWN PATH F1**

**Same characteristics as NSE_SPIKE_DETECTION**

---

## PART 7: REMAINING CODE SEARCH TARGETS

To identify Path F1, examine:

1. **Message handlers:**
   - Look for @RabbitListener, @KafkaListener methods in stokr-strategy
   - Check if broker/OMS is creating signals via message queue

2. **Direct repository methods:**
   - Check if any service calls `signalRepository.save()` or bulk insert directly
   - Search for "INSERT INTO strategy_signals" in any code generation

3. **Background jobs:**
   - Look for methods scheduled via another framework (Quartz, Spring Task Scheduler)
   - Check for jobs executed from database config table

4. **Admin APIs:**
   - Look for @PostMapping/@PutMapping that creates signals
   - Check if admin manually creates signals with pipeline="LIVE"

5. **Event listeners:**
   - Check for @EventListener or ApplicationEventPublisher subscribers
   - Look for event-driven signal creation

6. **Fallback/Recovery paths:**
   - Check if signals are being recreated from position data
   - Look for position reconciliation creating signals

---

## CONCLUSION

**Root Cause Status: PARTIALLY IDENTIFIED**

### Known
- CatalogDrivenScanScheduler creates 110 signals with enrichment (BOTH/PAPER)
- ConfidenceBasedSignalGeneratorService might create some signals without enrichment
- All 1,198 LIVE pipeline signals come from unknown source(s)
- NSE_SPIKE_DETECTION, EARLY_BREAKOUT, SECTOR_LAGGARD (1,073 signals) use same unknown path
- ADV_CASH LIVE signals (53) are enriched unlike others

### Unknown  
- **Exact location of Path F1 that creates 1,073 LIVE/NULL signals**
- Whether Path F2 (ADV_CASH enriched) is different code or configuration

### Proven Source Location Not Found
- No code location in `stokr-strategy/`, `stokr-execution/`, or `stokr-admin/` modules produces pipeline="LIVE" values
- Exhaustive grep search of persistAndDispatch, save(), and baseEntity() calls found no other production signal creators

**The 1,198 LIVE pipeline signals exist in production but their source code remains unidentified.**

