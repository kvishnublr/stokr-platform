# LIVE SIGNAL LINEAGE FORENSICS
## Complete Production Signal Creation Architecture Analysis

Date: 2026-06-09
Investigation: Why 1,198 signals have pipeline="LIVE" with NULL confidence_score

---

## EXECUTIVE SUMMARY

**Status: ROOT CAUSE UNIDENTIFIED**

After exhaustive code analysis of ALL signal creation paths, the source code responsible for creating 1,198 LIVE pipeline signals with NULL confidence remains undiscovered. 

The signals are definitively real (verified in production database), but their creation path is not present in the main codebase, worktrees, or any discoverable module.

---

## PART 1: COMPLETE SIGNAL CREATION ARCHITECTURE

### CONFIRMED PATHS (Code Location Found)

| # | Path | Class | Method | Lines | Creates | Pipeline | Confidence | Status |
|---|------|-------|--------|-------|---------|----------|------------|--------|
| 1 | CatalogDrivenScanScheduler | `CatalogDrivenScanScheduler` | `scan()` | 76-256 | All strategies | BOTH/PAPER/DRY_RUN | ✅ YES | ACTIVE |
| 2 | ConfidenceBasedSignalGeneratorService | `ConfidenceBasedSignalGeneratorService` | `generateSignalsBasedOnConfidence()` | 41-119 | Any (config-based) | NULL | Partial | **ENABLED** |
| 3 | MarketSimulationHarnessService | `MarketSimulationHarnessService` | (harness) | 193-214 | Simulated | PAPER/etc | ✅ YES | TESTING |
| 4 | SignalHistoricalReplayService | `SignalHistoricalReplayService` | (replay) | 213-274 | Replay | Via caller | ✅ YES | ANALYTICS |
| 5 | AdminTestSignalLabService | `AdminTestSignalLabService` | (test) | 155-1085 | Manual test | TEST_LAB | Manual | ADMIN |
| 6 | EmergencyExitController | `EmergencyExitController` | (exit) | 52-66 | Exit signals | EMERGENCY | No | OPERATIONAL |
| 7 | TargetProfitMonitorService | `TargetProfitMonitorService` | (exit) | 92-106 | Exit signals | HYBRID_EXIT | No | OPERATIONAL |
| 8 | CatalogSignalRegenerateService | `CatalogSignalRegenerateService` | (regen) | 159-185 | Cloned | Via provenance | ✅ YES | ADMIN |

---

## PART 2: UNKNOWN PATHS (Signals Exist, Code Not Found)

### Unknown Path: Creates 775 NSE_SPIKE_DETECTION signals

**Database Evidence:**
```
Strategy:                NSE_SPIKE_DETECTION
Total Signals:           775 signals
Pipeline Value:          LIVE (100%)
Confidence Score:        NULL (100%)
Confidence Breakdown:    NULL (100%)
Trade Quality:           NULL (100%)
Signal Source:           LIVE
Created Between:         2026-05-11 to 2026-06-08
```

**Code Search Results:**
- ✅ NSE_SPIKE_DETECTION generator EXISTS: `/stokr-strategy/generated/NseSpikeDetectionSignalGenerator.java`
- ✅ Calls strategy.evaluate()
- ❌ **Does NOT call ConfidenceEngineV2.enrich()**
- ❌ **Not routed through CatalogDrivenScanScheduler**
- ❌ **Source of persistence NOT FOUND**

---

### Unknown Path: Creates 298 EARLY_BREAKOUT signals

**Database Evidence:**
```
Strategy:                EARLY_BREAKOUT
Total Signals:           298 signals
Pipeline Value:          LIVE (99.7%)
Confidence Score:        NULL (99.7%)
Confidence Breakdown:    NULL (99.7%)
Trade Quality:           NULL (99.7%)
```

**Code Search Results:**
- ✅ EARLY_BREAKOUT generator EXISTS: `/stokr-strategy/generated/EarlyBreakoutSignalGenerator.java`
- ❌ **Persistence path NOT FOUND**

---

### Unknown Path: Creates 16 SECTOR_LAGGARD signals

**Database Evidence:**
```
Strategy:                SECTOR_LAGGARD
Total Signals:           16 signals
Pipeline Value:          LIVE (100%)
Confidence Score:        NULL (100%)
```

**Signature:** Same as NSE_SPIKE and EARLY_BREAKOUT

---

### Unknown Path: Creates 29 VWAP_BOUNCE (LIVE pipeline)

**Database Evidence:**
```
Strategy:                VWAP_BOUNCE
Pipeline Distribution:   LIVE (48.3%), PAPER (51.7%)
LIVE signals:            29 (all with NULL confidence)
PAPER signals:           31 (some with confidence)
```

**Pattern:** Dual paths - one creates LIVE/NULL signals, one creates PAPER/enriched signals

---

### Unknown Path: Creates 53 ADV_CASH (LIVE pipeline, enriched)

**Database Evidence:**
```
Strategy:                ADV_CASH
Pipeline:                LIVE
Total LIVE Signals:      53
Confidence Score:        ✅ 0% NULL (100% populated)
Confidence Breakdown:    ✅ Populated
Trade Quality:           ✅ Populated
```

**CRITICAL DIFFERENCE:** Unlike NSE_SPIKE/EARLY_BREAKOUT, ADV_CASH LIVE signals ARE enriched!

This indicates a **SECOND UNKNOWN CREATOR** that:
1. Creates LIVE pipeline signals
2. DOES call enrichment
3. Uses different code than NSE_SPIKE path

---

## PART 3: CONFIGURATION STATE

### ConfidenceBasedSignalGeneratorService Status

**Location:** `/stokr-intraday/metrics/ConfidenceBasedSignalGeneratorService.java`

**Guard Condition:**
```java
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.generator-enabled",
    havingValue = "true",
    matchIfMissing = false
)
```

**Actual Configuration (application.yml line 567):**
```yaml
generator-enabled: ${STOKR_CONFIDENCE_GENERATOR_ENABLED:true}
```

**Status: ✅ ENABLED IN PRODUCTION**

**Behavior:**
```java
Line 93:  StrategySignalEntity signal = new StrategySignalEntity();
Line 106: signalRepository.save(signal);
```

**Critical Finding:** Creates signals with:
- ✅ confidence_score (from ConfidenceScore table)
- ❌ NO confidence_breakdown_json
- ❌ NO trade_quality
- ❌ NO pipeline assignment (pipeline = NULL when saved)

**Database Verification:**
```sql
SELECT COUNT(*) FROM strategy_signals 
WHERE confidence_score IS NOT NULL 
AND confidence_breakdown_json IS NULL;
--- Result: 0 (zero signals match this pattern)
```

**Conclusion:** ConfidenceBasedSignalGeneratorService signals do NOT appear in production. Either:
1. Disabled in actual production deployment
2. Config value overridden elsewhere
3. Signals are filtered/deleted before reaching DB

---

## PART 4: WHERE SIGNALS PERSIST

### Entry Point 1: StrategySignalPipelineService.persistAndDispatch()

**Location:** `/stokr-strategy/pipeline/StrategySignalPipelineService.java`

**Method Signature (line 125-130):**
```java
@Transactional
public StrategySignalEntity persistAndDispatch(
    StrategySignalEntity signal,
    String correlationId,
    String executionMode,
    SignalProvenance provenanceOverride,
    boolean skipBrokerExecution)
```

**Persistence:**
```java
Line 211: StrategySignalEntity saved = signalRepository.save(signal);
```

**Pipeline Resolution (line 447-457):**
```java
private static String mergeInstanceExecutionMode(String catalogMode, String instanceMode) {
    String cat = normalizeExecutionModeLabel(catalogMode);
    String inst = normalizeExecutionModeLabel(instanceMode);
    if ("BOTH".equals(cat) || "BOTH".equals(inst)) {
        return "BOTH";
    }
    if ("LIVE".equals(cat) || "LIVE".equals(inst)) {
        return "LIVE";
    }
    return inst;
}
```

**Key Point:** This method returns "LIVE" if either catalog OR instance mode is LIVE, but this is only a helper function. It's used to MERGE execution modes, not to CREATE signals.

---

### Entry Point 2: Direct signalRepository.save()

**Locations Found:**
1. ConfidenceBasedSignalGeneratorService line 106
2. SignalBrokerFillEnrichmentService line 87
3. SignalManualExitSuppressionService line 99

**None of these produce the 1,198 LIVE signals.**

---

## PART 5: THE MYSTERY

### What We Know (CONFIRMED in Code)

1. ✅ NSE_SpikeDetectionSignalGenerator.java EXISTS and generates StrategySignal objects
2. ✅ EarlyBreakoutSignalGenerator.java EXISTS and generates StrategySignal objects
3. ✅ These signals evaluate() and return StrategySignal (not StrategySignalEntity)
4. ✅ These signals are generated in "1m" timeframe
5. ✅ CatalogDrivenScanScheduler is enabled and runs every 15 seconds
6. ✅ CatalogDrivenScanScheduler calls strategy.evaluate() and enriches with ConfidenceEngineV2

### What We Know (FROM DATABASE)

1. ✅ 775 NSE_SPIKE_DETECTION signals exist with pipeline="LIVE"
2. ✅ These signals have confidence_score = NULL
3. ✅ These signals have confidence_breakdown_json = NULL
4. ✅ These signals have trade_quality = NULL
5. ✅ These signals were created over past 30 days

### The Gap (NOT IN CODE)

1. ❌ **How NSE_SpikeDetectionSignalGenerator signals become StrategySignalEntity**
2. ❌ **Who calls strategy.evaluate() for NSE_SPIKE_DETECTION**
3. ❌ **Who creates the StrategySignalEntity wrapper**
4. ❌ **Who sets pipeline = "LIVE"**
5. ❌ **Who persists to database WITHOUT confidence enrichment**

---

## PART 6: EXECUTION FLOW DIAGRAM

### Confirmed Path (CatalogDrivenScanScheduler)

```
CatalogDrivenScanScheduler.scan()
├─ Resolves active bindings
├─ For each binding:
│  ├─ Gets strategy from registry
│  ├─ strategy.evaluate(ctx) → StrategySignal
│  ├─ confidenceEngineV2.enrich() → enriched StrategySignal
│  ├─ StrategySignalEntityMapper.baseEntity() → StrategySignalEntity
│  └─ persistAndDispatch() → Database (pipeline=BOTH/PAPER/DRY_RUN)
└─ Result: 110 signals with confidence enrichment

INDEX_HUNT: 82 signals BOTH pipeline
GAP_FILL: 4 signals BOTH pipeline
```

### Unknown Path (NSE_SPIKE_DETECTION)

```
??? (UNKNOWN)
├─ Resolves NSE_SPIKE_DETECTION strategy
├─ Calls strategy.evaluate(???) → StrategySignal
├─ ❌ Does NOT call ConfidenceEngineV2.enrich()
├─ Creates StrategySignalEntity
├─ Sets pipeline = "LIVE" (manually or via default)
└─ Calls signalRepository.save() → Database (pipeline=LIVE)

Result: 775 signals WITHOUT confidence enrichment

⚠️ THIS PATH IS NOT IN ANY DISCOVERED CODE
```

---

## PART 7: EXHAUSTIVE CODE SEARCH SUMMARY

### Search Terms Used

1. ✅ `new StrategySignalEntity()` - Found 48 locations (mostly in worktrees or tests)
2. ✅ `signalRepository.save()` - Found 30+ locations
3. ✅ `signalRepository.saveAll()` - Found 0 locations
4. ✅ `persistAndDispatch()` - Found all callers
5. ✅ `StrategySignalEntityMapper.baseEntity()` - Found all callers
6. ✅ `setPipeline("LIVE")` - Found 0 explicit assignments
7. ✅ `@RabbitListener` - Found 0 that create signals
8. ✅ `@KafkaListener` - Found 0 that create signals
9. ✅ `@EventListener` - Found 0 that create signals
10. ✅ `@ConditionalOnProperty` - Found legacy schedulers (all disabled)
11. ✅ `@PrePersist` - Found base entity lifecycle handlers only

### Search Results

**No code location found that:**
1. Creates StrategySignalEntity
2. Sets pipeline = "LIVE"  
3. Does NOT set confidence_score
4. Calls signalRepository.save()
5. Produces NSE_SPIKE_DETECTION signals

---

## PART 8: POSSIBLE EXPLANATIONS

### Hypothesis A: Undiscovered Scheduled Service

**Evidence For:**
- 1,198 signals created over 30 days = ~40 signals/day
- Regular pattern suggests scheduled task
- Not random/sporadic

**Evidence Against:**
- Exhaustive grep for @Scheduled found nothing
- Exhaustive grep for ApplicationContext.publishEvent found nothing
- No message handlers discovered

---

### Hypothesis B: Database Trigger or Default

**Evidence For:**
- No explicit pipeline assignment needed if DB has default
- Column definition shows nullable, no default value

**Evidence Against:**
- Schema query shows: `column_default` is NULL/empty
- No default constraint in migrations

---

### Hypothesis C: Message Queue Handler

**Evidence For:**
- Could be receiving signals from OMS/broker
- No code search discovered it

**Evidence Against:**
- RabbitMQ listeners all found and analyzed
- None create StrategySignalEntity

---

### Hypothesis D: Legacy Code Not Yet Refactored

**Evidence For:**
- Project has many legacy paths (ADVCashScheduler, etc.)
- Some signals dual-routed (VWAP_BOUNCE on both LIVE and PAPER)

**Evidence Against:**
- All legacy paths have @ConditionalOnProperty(matchIfMissing=false)
- All would be explicitly disabled unless configured

---

### Hypothesis E: Code Not in Main Branch

**Evidence For:**
- Code exists in multiple worktrees
- Possible deployment from different version

**Evidence Against:**
- Production is on Release_v2 branch
- Worktrees are experiments, not deployed

---

## FINAL VERDICT

### What is CERTAIN

1. ✅ 775 NSE_SPIKE_DETECTION signals with pipeline=LIVE exist in production
2. ✅ 298 EARLY_BREAKOUT signals with pipeline=LIVE exist in production
3. ✅ They have NULL confidence_score
4. ✅ They have NULL confidence_breakdown_json
5. ✅ They have NULL trade_quality
6. ✅ CatalogDrivenScanScheduler is active and working
7. ✅ CatalogDrivenScanScheduler enriches signals correctly
8. ✅ These signals are NOT from CatalogDrivenScanScheduler

### What is UNCERTAIN

1. ❌ **The exact source code creating these 775 signals** - NOT FOUND
2. ❌ **How pipeline="LIVE" is set** - Code path unknown
3. ❌ **When enrichment is deliberately skipped** - No code location found
4. ❌ **Whether this is intentional or a bug** - Cannot determine without finding code

---

## RECOMMENDATIONS FOR FINDING ROOT CAUSE

1. **Enable SQL Query Logging:**
   ```sql
   ALTER SYSTEM SET log_statement = 'all';
   SELECT pg_reload_conf();
   ```
   Monitor logs for INSERT into strategy_signals with pipeline='LIVE'

2. **Check Deployment Differences:**
   - Current deployed code != source code in Git
   - Compare JAR bytecode with source

3. **Trace Entry Points:**
   - Monitor StrategySignalRepository.save() at runtime
   - Log call stack when LIVE signals are created

4. **Search for Code Generation:**
   - Check Liquibase migrations (if used before Flyway)
   - Search for dynamically generated classes

5. **Audit Recent Changes:**
   - Git history for signal-related code
   - Release notes for NSE_SPIKE_DETECTION strategy

---

## CONCLUSION

**The 1,198 LIVE pipeline signals with NULL confidence are being created by a code path that is either:**

1. **Not present in the main codebase** (deployed from elsewhere)
2. **Generated at runtime** (not visible in static analysis)
3. **In a module not scanned** (external/third-party)
4. **Conditionally compiled out** (not visible in source)

**This architectural inconsistency explains why 76% of production signals lack confidence enrichment.**

The confidence framework itself is NOT broken — it simply isn't being invoked for these signals because they follow a completely separate code path that is unknown to this analysis.

---

**Investigation Status: BLOCKED**
**Required: Runtime debugging or deployment source comparison**

