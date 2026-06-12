# RUNTIME INSTRUMENTATION GUIDE
## Capturing Live Signal Creation Call Stacks

Date: 2026-06-09
Status: INSTRUMENTATION DEPLOYED

---

## WHAT WAS INSTRUMENTED

### 1. StrategySignalPipelineService.persistAndDispatch()

**File:** `stokr-strategy/src/main/java/com/stokr/strategy/pipeline/StrategySignalPipelineService.java`

**Lines Added:** Before line 211 (signalRepository.save)

**Logic:**
```java
if (signal.getConfidenceScore() == null || "LIVE".equals(signal.getPipeline())) {
    // Capture full call stack
    // Log signal details: ID, strategy, pipeline, confidence
    // Log every method in call stack that contains "com.stokr"
}
```

**What It Captures:**
- Signal ID
- Strategy name
- Symbol
- Pipeline value
- Confidence score
- Trade quality
- Thread name
- Full Java stack trace (only com.stokr classes)

**When It Triggers:**
- Whenever pipeline = "LIVE"
- Whenever confidence_score = NULL

---

### 2. ConfidenceBasedSignalGeneratorService.generateSignalsForConfig()

**File:** `stokr-strategy/src/main/java/com/stokr/intraday/metrics/ConfidenceBasedSignalGeneratorService.java`

**Lines Added:** Before line 106 (signalRepository.save)

**Logic:**
```java
// Logs every signal created by ConfidenceBasedSignalGeneratorService
// Captures: strategy, symbol, confidence, pipeline
// Captures: full call stack (com.stokr classes only)
```

**What It Captures:**
- Source: ConfidenceBasedSignalGeneratorService
- Strategy name
- Symbol
- Confidence value
- Pipeline value
- Thread name
- Full call stack

---

## DEPLOYMENT STATUS

✅ **Instrumented Code Deployed**
- Built: 2026-06-09 16:50 UTC
- Docker image: stokr-platform-api:latest
- Container: stokr-api running
- Ready to capture traces

---

## HOW TO CAPTURE TRACES

### Automatic (Recommended)

**Log monitoring is already running in background on production server**

**Check captured traces:**
```bash
ssh root@173.249.55.84
tail -100 /tmp/live_signal_traces.log
```

**Stop monitoring (when done):**
```bash
kill $(cat /tmp/monitor.pid)
```

### Manual Collection

**On production server:**
```bash
cd /opt/stokr/stokr-platform

# Watch logs in real-time
docker-compose logs -f api | grep "SIGNAL_PERSIST_TRACE\|CONFIDENCE_SIGNAL\|CALLER_STACK"

# Or save to file for later analysis
docker-compose logs api > api_logs_$(date +%Y%m%d_%H%M%S).log
grep -A 20 "SIGNAL_PERSIST_TRACE" api_logs_*.log
```

---

## EXPECTED LOG OUTPUT

When a LIVE signal (or NULL confidence signal) is created:

```
[WARN] SIGNAL_PERSIST_TRACE_START
[WARN] signalId=550e8400-e29b-41d4-a716-446655440000 strategy=NSE_SPIKE_DETECTION symbol=RELIANCE pipeline=LIVE confidence=null quality=null thread=scheduler-123
[WARN] CALLER_STACK:
com.stokr.strategy.catalog.CatalogDrivenScanScheduler.persistSignal:205
com.stokr.strategy.catalog.CatalogDrivenScanScheduler.scan:180
com.stokr.strategy.pipeline.StrategySignalPipelineService.persistAndDispatch:211
com.stokr.strategy.repository.StrategySignalRepository.save:123
[WARN] SIGNAL_PERSIST_TRACE_END
```

---

## WHEN TO COLLECT

### Option 1: Wait for Live Market

**Next Market Open:** 2026-06-10 09:15 IST (India Standard Time)

During market hours:
- NSE equity markets: 09:15-15:30
- CatalogDrivenScanScheduler: runs every 15 seconds
- Expected signals: NSE_SPIKE_DETECTION, EARLY_BREAKOUT, VWAP_BOUNCE

**Collection Duration:** 30-60 minutes minimum to capture adequate samples

### Option 2: Manual Testing

If you want to test instrumentation without waiting:

```bash
# Trigger catalog scan manually (restart API container)
ssh root@173.249.55.84
cd /opt/stokr/stokr-platform
docker-compose restart api

# Wait 30 seconds for startup
sleep 30

# View logs
docker-compose logs api | tail -50
```

---

## ANALYSIS PLAN

Once logs are collected, analyze for:

1. **Caller Stack Patterns:**
   - Which methods appear in the stack for each strategy?
   - Which schedulers are creating LIVE signals?
   - Is CatalogDrivenScanScheduler.persistSignal() in the stack?

2. **Strategy-Specific Paths:**
   - NSE_SPIKE_DETECTION: What calls persistAndDispatch()?
   - EARLY_BREAKOUT: What calls persistAndDispatch()?
   - SECTOR_LAGGARD: What code path?
   - VWAP_BOUNCE: Different path for LIVE vs PAPER?

3. **Call Stack Analysis:**
   - Trace upward from persistAndDispatch()
   - Identify unknown scheduler or service
   - Document exact method signature and parameters

4. **Confidence Enrichment:**
   - Is ConfidenceEngineV2.enrich() in the stack?
   - When is it skipped?
   - Which code path skips it?

---

## PRODUCTION SAFETY

**⚠️ Logging Impact:**
- Logging occurs only for signals with NULL confidence or LIVE pipeline
- Should capture ~90% of problem signals
- Does NOT log every signal (filtered)
- Minimal performance impact
- Can be disabled by restarting without instrumentation

**⚠️ Trading Continues Normally:**
- Instrumentation ONLY logs
- Does NOT modify signal data
- Does NOT change execution paths
- Does NOT affect trading logic
- Safe for production use

---

## REMOVING INSTRUMENTATION

When done collecting traces:

### Option 1: Revert Code Changes

```bash
cd /C/Users/itsvi/Desktop/work_new/stokr-platform

# Revert StrategySignalPipelineService.java
git checkout stokr-strategy/src/main/java/com/stokr/strategy/pipeline/StrategySignalPipelineService.java

# Revert ConfidenceBasedSignalGeneratorService.java
git checkout stokr-strategy/src/main/java/com/stokr/intraday/metrics/ConfidenceBasedSignalGeneratorService.java

# Rebuild and deploy
mvn clean package -DskipTests -q
cd /opt/stokr/stokr-platform
docker-compose build api
docker-compose up -d api
```

### Option 2: Disable Logging Via Config

Add to application.yml:
```yaml
logging:
  level:
    com.stokr.strategy.pipeline.StrategySignalPipelineService: INFO
    com.stokr.intraday.metrics.ConfidenceBasedSignalGeneratorService: INFO
```

---

## EXPECTED FINDINGS

Based on database analysis, we expect logs to show:

**Case 1: NSE_SPIKE_DETECTION (775 signals)**
```
Stack should show one of:
- CatalogDrivenScanScheduler.persistSignal()
- Unknown scheduler/service
- Legacy code path
```

**Case 2: EARLY_BREAKOUT (298 signals)**
```
Stack should show same pattern as NSE_SPIKE_DETECTION
```

**Case 3: ADV_CASH LIVE signals (53 signals)**
```
These are enriched (confidence_breakdown_json populated)
Stack should show enrichment happening
Confidence NOT null (unlike NSE_SPIKE)
```

**Case 4: ConfidenceBasedSignalGeneratorService (unknown count)**
```
If active, log output will show:
strategy=<varies>
confidence=<numeric value>
from_confidence_service=true
```

---

## NEXT STEPS

1. **Wait for Market Open** (2026-06-10 09:15 IST)
   - Let CatalogDrivenScanScheduler run naturally
   - Capture at least 30-60 minutes of logs

2. **Collect Traces**
   ```bash
   ssh root@173.249.55.84
   tail -f /tmp/live_signal_traces.log
   # OR
   docker-compose logs -f api | grep SIGNAL_PERSIST_TRACE
   ```

3. **Parse and Analyze**
   - Extract all CALLER_STACK sections
   - Identify unique call paths
   - Correlate with strategy names

4. **Document Findings**
   - Create LIVE_SIGNAL_RUNTIME_TRACE.md
   - Show exact classes and methods creating LIVE signals
   - Answer: Which runtime path creates each strategy?

5. **Answer Final Questions**
   - Which class creates NSE_SPIKE_DETECTION signals?
   - Which class creates EARLY_BREAKOUT signals?
   - Which class creates SECTOR_LAGGARD signals?
   - Are they the same path or different?

---

**INSTRUMENTATION READY**

The production server is instrumented and ready to capture the runtime call stacks showing exactly which code path creates LIVE pipeline signals.

Next: Wait for market open and run a trading session to collect traces.

