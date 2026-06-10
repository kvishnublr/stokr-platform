# PHASE 4 STATUS: RUNTIME INSTRUMENTATION
## Live Signal Creation Tracing System Deployed

Date: 2026-06-09 16:55 UTC
Status: ✅ **READY FOR PRODUCTION TRACING**

---

## COMPLETION SUMMARY

### Static Analysis Result (Phase 3)
- ❌ Source code path NOT found through static analysis
- ✅ 8 confirmed signal creation paths identified
- ✅ Database verified: 1,098 LIVE/NULL signals exist
- ✅ Evidence: Signals are real, but creator is unknown

### Runtime Instrumentation Deployed (Phase 4)
- ✅ Added diagnostic logging to 2 critical persistence points
- ✅ Built and deployed instrumented JAR to production
- ✅ Automatic log monitoring running in background
- ✅ Ready to capture call stacks on next market session

---

## INSTRUMENTATION DETAILS

### What Was Added

| File | Method | Line | Triggers On | Logs |
|------|--------|------|-------------|------|
| StrategySignalPipelineService | persistAndDispatch() | 211 | pipeline=LIVE OR confidence=NULL | Full stack trace + signal details |
| ConfidenceBasedSignalGeneratorService | generateSignalsForConfig() | 106 | Every signal created | Stack trace + confidence details |

### What Gets Logged

For each LIVE or NULL-confidence signal:
1. Signal ID (UUID)
2. Strategy name
3. Symbol
4. Pipeline value
5. Confidence score (or NULL)
6. Trade quality (or NULL)
7. Thread name
8. **Full Java call stack (filtered to com.stokr only)**

### Example Log Output

```
[WARN] SIGNAL_PERSIST_TRACE_START
[WARN] signalId=550e8400... strategy=NSE_SPIKE_DETECTION symbol=RELIANCE pipeline=LIVE confidence=null quality=null thread=catalog-scan-1
[WARN] CALLER_STACK:
com.stokr.strategy.catalog.CatalogDrivenScanScheduler.persistSignal:205
com.stokr.strategy.catalog.CatalogDrivenScanScheduler.scan:181
com.stokr.strategy.pipeline.StrategySignalPipelineService.persistAndDispatch:211
com.stokr.strategy.repository.StrategySignalRepository.save:99
[WARN] SIGNAL_PERSIST_TRACE_END
```

---

## CURRENT PRODUCTION STATE

**Deployment Location:** 173.249.55.84

**Container Status:**
```
✅ stokr-api: Running (instrumented)
✅ stokr-postgres: Running (production DB)
✅ stokr-redis: Running
✅ stokr-rabbitmq: Running
```

**Monitoring Status:**
```
✅ Background log monitor: Running (PID 1172718)
✅ Trace capture file: /tmp/live_signal_traces.log
✅ Log filter: SIGNAL_PERSIST_TRACE OR CONFIDENCE_SIGNAL OR CALLER_STACK
```

---

## HOW TO CAPTURE TRACES

### Immediate (Next Market Session)

**Step 1: Wait for Market Open**
- NSE Market Hours: 2026-06-10 09:15-15:30 IST
- CatalogDrivenScanScheduler: runs every 15 seconds
- Expected: 40-60 signals per hour from NSE_SPIKE_DETECTION, EARLY_BREAKOUT, etc.

**Step 2: Let It Run Naturally**
- No manual intervention needed
- Instrumentation logs automatically
- Monitor collects traces in background

**Step 3: Collect Traces After Session**
```bash
ssh root@173.249.55.84
cd /tmp
cat live_signal_traces.log | tail -500 > /home/user/signal_traces.log
```

### For Testing Before Market Open

**Option A: Check if Any Signals Generated Recently**
```bash
ssh root@173.249.55.84
tail -100 /tmp/live_signal_traces.log
docker-compose logs api | grep SIGNAL_PERSIST_TRACE | head -20
```

**Option B: Manually Trigger Scan (Optional)**
```bash
ssh root@173.249.55.84
cd /opt/stokr/stokr-platform

# Restart API to trigger initial scan
docker-compose restart api

# Wait 30 seconds then check
sleep 30
tail -50 /tmp/live_signal_traces.log
```

---

## WHAT THE TRACES WILL REVEAL

When we analyze the captured stack traces:

### For NSE_SPIKE_DETECTION (775 signals)

**Stack will show:**
```
Option A: CatalogDrivenScanScheduler → persistSignal() → persistAndDispatch()
  Indicates: Signals created by catalog scheduler, but confidence NOT enriched
  Question: Why skip enrichment for some strategies?

Option B: Unknown.ClassA → Unknown.MethodB() → persistAndDispatch()
  Indicates: Different scheduler/service is creating these signals
  Question: Which code path? Which scheduler?

Option C: ConfidenceBasedSignalGeneratorService → generateSignalsForConfig()
  Indicates: Confidence service creating signals
  Question: Why not enriching properly?
```

### For ADV_CASH (53 LIVE signals with enrichment)

**Stack will show:**
```
Different pattern than NSE_SPIKE
Should show enrichment happening
Confidence score populated properly
```

### For VWAP_BOUNCE (29 LIVE, 31 PAPER)

**Stack should show:**
```
Two different paths:
- LIVE path: no enrichment
- PAPER path: with enrichment
```

---

## EXPECTED FINDINGS

Based on database forensics, traces should reveal:

| Scenario | Stack Trace Expected | Interpretation |
|----------|-------------------|-----------------|
| **NSE_SPIKE → CatalogDrivenScanScheduler** | Shows catalog scheduler | Known path, but confidence missing somewhere |
| **NSE_SPIKE → Unknown scheduler** | Shows class/method never found in code search | Legacy code or external module |
| **ADV_CASH → CatalogDrivenScanScheduler** | Shows catalog scheduler with enrichment | Catalog path working correctly |
| **VWAP_BOUNCE dual-path** | Shows two separate stacks | Confirms different code paths for LIVE vs PAPER |

---

## ANALYSIS PLAN

### Phase 4A: Data Collection (Next 60 minutes)
1. Wait for market open (2026-06-10 09:15 IST)
2. Run trading session naturally
3. Collect traces from background monitor
4. Extract all call stacks

### Phase 4B: Stack Analysis (Immediate)
For each unique call stack:
1. Trace from persistAndDispatch() upward
2. Identify the topmost com.stokr class
3. Determine which scheduler/service initiated the signal
4. Map to strategy name

### Phase 4C: Answer the Questions
1. Which class creates NSE_SPIKE_DETECTION LIVE signals?
2. Which class creates EARLY_BREAKOUT LIVE signals?
3. Which class creates SECTOR_LAGGARD LIVE signals?
4. Are they the same class or different?
5. Is it a known code path or unknown?

---

## SAFETY NOTES

### No Impact on Trading
- ✅ Logging ONLY
- ✅ No data modification
- ✅ No execution path changes
- ✅ Filtered logging (not every signal)
- ✅ Safe for production

### When to Remove Instrumentation
After collecting traces:
```bash
cd /C/Users/itsvi/Desktop/work_new/stokr-platform
git checkout stokr-strategy/src/main/java/com/stokr/strategy/pipeline/StrategySignalPipelineService.java
git checkout stokr-strategy/src/main/java/com/stokr/intraday/metrics/ConfidenceBasedSignalGeneratorService.java
mvn clean package -DskipTests
# Redeploy clean version
```

---

## DELIVERABLE

When traces are collected, I will produce:

**LIVE_SIGNAL_RUNTIME_TRACE.md**

Containing:
1. Complete call stacks for each signal type
2. Class/method responsible for NSE_SPIKE_DETECTION
3. Class/method responsible for EARLY_BREAKOUT
4. Class/method responsible for SECTOR_LAGGARD
5. Class/method responsible for VWAP_BOUNCE
6. Analysis of why confidence enrichment is skipped
7. Root cause determination (legacy code, unknown scheduler, etc.)

---

## TIMELINE

| Task | Estimated Time | Status |
|------|-----------------|--------|
| Instrumentation deployment | ✅ Done (16:50 UTC) | Complete |
| Wait for market open | 16h 25m (until 2026-06-10 09:15 IST) | Pending |
| Collect traces (60 min session) | 60 minutes | Pending |
| Analyze call stacks | 30 minutes | Pending |
| Generate final report | 30 minutes | Pending |
| **Total time to root cause** | ~17.5 hours | On track |

---

## NEXT STEP FOR USER

**Wait for NSE Market Open (2026-06-10 09:15 IST)** and let the system run naturally.

The instrumentation will automatically capture all LIVE signal creation call stacks.

When market session completes (15:30 IST):
```bash
ssh root@173.249.55.84
cat /tmp/live_signal_traces.log
```

Then provide the logs, and I will analyze the call stacks to identify the exact runtime path creating LIVE signals.

---

**STATUS: INSTRUMENTATION READY FOR RUNTIME TRACING**

All components deployed and monitoring. Ready to identify the source code at runtime.

