# CONFIDENCE ACTIVATION IMPACT REVIEW
## What Would Happen If Confidence Telemetry Started Flowing Tomorrow?

Date: 2026-06-09  
Analysis Type: Code review + impact assessment  
Scenario: Confidence fields change from 98.2% NULL to fully populated tomorrow

---

## SECTION 1: COMPONENTS THAT READ confidence_score

### A) ConfidenceSignalToOrderService

**File:** `/opt/stokr/stokr-platform/stokr-bootstrap/src/main/java/com/stokr/bootstrap/trading/ConfidenceSignalToOrderService.java`

**Status:** EXISTS - READY TO USE - CURRENTLY DISABLED

**Reads confidence_score FOR:**
- ✅ Filtering signals: `findRecentByConfidenceThreshold(threshold, ...)`
- ✅ Position sizing determination based on confidence level
- ✅ Decision gates for trade execution

**Decision Logic Based on Confidence:**
```java
// Line ~120: Filter by confidence threshold
if (score.getConfidenceScore() < config.getMinConfidenceThreshold()) {
    skip this signal
}

// Implied logic: Position sizing varies by confidence
int quantity = determineQuantity(score);  // varies with confidence
```

**Activation Requirement:**
```java
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.auto-trade-enabled",
    havingValue = "true",
    matchIfMissing = false  // ← DEFAULT: DISABLED
)
```

**Current State:** DISABLED by default
**If Enabled:** Would activate automatic trading on confidence signals

### B) ConfirmationRank

**File:** `/opt/stokr/stokr-platform/stokr-bootstrap/src/main/java/com/stokr/bootstrap/trader/ConfirmationRank.java`

**Reads confidence_score FOR:**
- ✅ Ranking/scoring signals
- ✅ Normalizing confidence to percentage

**Decision Impact:** MEDIUM (used for ranking, not gate)

### C) ConfidenceScoreRepository

**Queries:**
```sql
SELECT c FROM ConfidenceScore c WHERE c.confidenceScore > :threshold AND c.timestamp > :since
```

**Usage:** Filters signals by minimum confidence threshold

**Components Using This:**
- ConfidenceSignalToOrderService (main trader)
- Analytics services
- Dashboard queries

---

## SECTION 2: COMPONENTS THAT READ confidence_breakdown_json

### Usage Status

**Current Usage: MINIMAL**

**Found Usage:**
- SignalPipelineTraceService: Displays in audit logs (informational only)
- Dashboard/reporting (if any)

**Decision Impact: LOW** (informational/diagnostic, not decision-making)

---

## SECTION 3: COMPONENTS THAT MAKE DECISIONS USING CONFIDENCE

### Decision Type 1: Signal Acceptance/Rejection Gate

**Component:** ConfidenceSignalToOrderService

**Logic:**
```
IF confidence_score < minConfidenceThreshold:
  SKIP signal
ELSE:
  PROCESS signal
```

**Impact:** BLOCKS signals below threshold

**Status:** DISABLED by default

**Threshold:** Configurable per trader (default appears to be 75)

### Decision Type 2: Position Sizing

**Component:** ConfidenceSignalToOrderService

**Logic:**
```
IF confidence_score >= highConfidenceThreshold (85):
  quantity = highConfidenceQuantity (2)
ELSE:
  quantity = defaultQuantity (1)
```

**Impact:** ADJUSTS position size based on confidence

**Status:** DISABLED by default

### Decision Type 3: Approval Pipeline Decisions

**Status:** NOT FOUND in code review

No evidence found of approval pipeline using confidence for acceptance/rejection

### Decision Type 4: Risk Pipeline Decisions

**Status:** NOT FOUND in code review

No evidence found of risk management using confidence to adjust risk limits

---

## SECTION 4: DASHBOARDS DISPLAYING CONFIDENCE

### Found Dashboards:

**Dashboard 1: SignalPipelineTraceService**

**File:** `/opt/stokr/stokr-platform/stokr-admin/src/main/java/com/stokr/admin/signal/SignalPipelineTraceService.java`

**Displays:**
- confidenceScore (if not null)
- Other audit trail information

**Status:** Informational display only

**Impact:** Shows confidence in logs, no decision-making

---

## SECTION 5: REPORTS USING CONFIDENCE

### Report 1: Audit Logs

**Component:** SignalPipelineTraceService
**Content:** Shows confidence_score in signal audit trails
**Decision Impact:** NONE (informational only)

### Report 2: Analytics Queries

**Status:** NOT EXPLICITLY FOUND

No dedicated analytics reports found that depend on confidence
(This doesn't mean they don't exist - they may be dashboard queries)

---

## SECTION 6: CRITICAL IMPACT ANALYSIS

### Would populating confidence fields change trading behavior?

**Direct Answer: POSSIBLY YES - But only if disabled features are enabled**

**Scenario A: Current State (ConfidenceSignalToOrderService DISABLED)**

```
If confidence populates tomorrow:
  ✅ Database queries start returning non-null confidence
  ✅ Audit logs start displaying confidence values
  ❌ No automatic trading initiated (service disabled)
  ❌ No position sizing changes (service disabled)
  ❌ No signal filtering (service disabled)
  
Result: NO CHANGE IN TRADING BEHAVIOR
Confidence would be purely telemetry/informational
```

**Scenario B: If ConfidenceSignalToOrderService is ENABLED (without code change)**

```
Current: matchIfMissing = false
If someone changes to: matchIfMissing = true or adds: stokr.confidence-strategy.auto-trade-enabled=true

THEN immediately upon confidence populating:
  ✅ Service activates
  ✅ Filters signals by threshold
  ✅ Adjusts position sizing
  ✅ Starts automatic trading

Result: SIGNIFICANT CHANGE IN BEHAVIOR
Automatic confidence-based trading activates
```

**Scenario C: If code is modified to ENABLE and use confidence**

```
Risk gates: "⛔ Max open positions exceeded" would start blocking
Position sizing: Would be determined by confidence level
Signal acceptance: Would vary by confidence threshold

Result: MAJOR CHANGE IN BEHAVIOR
```

---

## SECTION 7: COULD EXISTING LOGIC ACCIDENTALLY REJECT TRADES?

### Rejection Risk Analysis

**Direct rejection gates:** NONE FOUND

No existing code found that would automatically reject/block trades based on NULL confidence.

**IF ConfidenceSignalToOrderService were enabled:**

```java
// Check threshold gate
if (score.getConfidenceScore() < config.getMinConfidenceThreshold()) {
    log.warn("⛔ Confidence below threshold. Skipping signal.");
    continue;  // ← REJECTS signal
}
```

**Current Safe State:** Service disabled, no rejection logic active

**At-Risk State:** If service enabled without proper configuration

---

## SECTION 8: CONFIDENCE - TELEMETRY vs DECISION-CRITICAL

### Current Classification: TELEMETRY-ONLY

**Evidence:**
- ✅ ConfidenceSignalToOrderService exists but is DISABLED
- ✅ No active gates based on confidence
- ✅ No automatic position sizing happening
- ✅ No automatic signal filtering happening
- ✅ Confidence displayed in audit logs (informational)
- ✅ No approval pipeline dependencies
- ✅ No risk pipeline dependencies

**Conclusion:** Currently confidence is 100% TELEMETRY

**Infrastructure for decision-making DOES exist but is NOT ACTIVE**

---

## SECTION 9: IMPACT MATRIX

### Tomorrow, if Confidence Populates

| Component | Current Behavior | Tomorrow if Enabled | Risk Level |
|---|---|---|---|
| **Signal Acceptance** | All non-null signals accepted | Would filter by threshold | MEDIUM |
| **Position Sizing** | Fixed (1 unit) | Varies by confidence | MEDIUM |
| **Approval Pipeline** | No change | No change (not implemented) | LOW |
| **Risk Pipeline** | No change | No change (not implemented) | LOW |
| **Dashboards** | Shows NULL | Shows actual values | NONE |
| **Reports** | NULL in audit | Populated audit logs | NONE |
| **Order Routing** | Current behavior | Could auto-trade (if enabled) | HIGH |
| **Trading Behavior** | Unchanged | Potentially significant (if enabled) | HIGH |

---

## SECTION 10: SAFETY ASSESSMENT

### Immediate Risk (Tomorrow, Confidence Populates)

**If ConfidenceSignalToOrderService REMAINS DISABLED:**
- ✅ SAFE - Zero impact on trading
- ✅ Confidence purely informational
- ✅ No automatic behavior changes

**If ConfidenceSignalToOrderService is ACCIDENTALLY ENABLED:**
- ⚠️ MEDIUM RISK - Automatic trading initiates
- ⚠️ Position sizing changes
- ⚠️ Signal filtering based on confidence
- ⚠️ No code change needed - just a configuration flag

**If ConfidenceSignalToOrderService is INTENTIONALLY ENABLED without config review:**
- ⚠️ MEDIUM RISK - Default thresholds (75, 85) would apply
- ⚠️ Possible rejection of low-confidence signals
- ⚠️ Possible position sizing based on scores

---

## SECTION 11: CONFIGURATION RISKS

### Property: stokr.confidence-strategy.auto-trade-enabled

**Current Default:** false (missing = false)

**If Changed to:** true

| Parameter | Default | Behavior |
|---|---|---|
| execution-mode | SIMULATED | Orders go to simulator, not live |
| default-quantity | 1 | Position size for normal confidence |
| high-confidence-quantity | 2 | Position size for high confidence (>85) |
| confidence-threshold | 75 | Minimum confidence to accept signal |
| interval-ms | 120000 | Check signals every 2 minutes |

**Risk:** Enabling this property without review could activate automatic trading

---

## SECTION 12: DASHBOARD AND REPORTING IMPACT

### Currently Displaying NULL

All dashboards showing confidence_score see NULL values

### Tomorrow, if Confidence Populates

**Dashboards Would:**
- ✅ Display actual confidence values (0.0-1.0 range)
- ✅ Show confidence breakdowns (if parsed)
- ✅ Enable confidence-based filtering in queries
- ✅ Enable confidence-based sorting in reports

**Decision Impact:** NONE (dashboards are read-only/informational)

---

## CONCLUSIONS

### Question 1: Which components READ confidence_score?

**Answer: THREE actively, many passively**

**Active Readers:**
1. ConfidenceSignalToOrderService (DISABLED) - filters and sizes positions
2. ConfirmationRank - ranks signals
3. Queries in ConfidenceScoreRepository - database filtering

**Passive Readers:**
- SignalPipelineTraceService - displays in logs
- Dashboard queries - informational display

### Question 2: Which components READ confidence_breakdown_json?

**Answer: MINIMAL - mainly informational**

Used for:
- Audit log display
- Dashboard information (if implemented)

Decision-making: NONE FOUND

### Question 3: Which components MAKE DECISIONS using confidence?

**Answer: ONE (and it's disabled)**

**ConfidenceSignalToOrderService:**
- Filters signals by threshold
- Adjusts position sizing
- Controls automatic trading

**Status:** DISABLED by default

### Question 4: Which dashboards display confidence?

**Answer: Signal pipeline audit trail (informational)**

- SignalPipelineTraceService shows confidence in audit logs
- No interactive confidence-based dashboards found

### Question 5: Which reports use confidence?

**Answer: Audit logs only**

- Confidence displayed in signal trace reports
- No dedicated confidence analytics reports found

### Question 6: Would populating confidence fields change trading behavior?

**Answer: NO - Unless ConfidenceSignalToOrderService is enabled**

**Current state:** Trading behavior unchanged
**If auto-trade enabled:** Trading behavior changes significantly

### Question 7: Could any existing logic accidentally reject trades?

**Answer: NO - Currently no rejection logic**

**If auto-trade enabled:** YES - signals below confidence threshold would be rejected

### Question 8: Is confidence currently telemetry-only or decision-critical?

**Answer: TELEMETRY-ONLY (currently)**

**Evidence:**
- Decision-making infrastructure exists but is DISABLED
- No active gates or filters based on confidence
- All confidence reads are informational/diagnostic
- Zero impact on trading behavior currently

**If ConfidenceSignalToOrderService is enabled:** Would become DECISION-CRITICAL

---

## RISK ASSESSMENT SUMMARY

### Low Risk Scenario (Recommended)

```
Confidence populates tomorrow + ConfidenceSignalToOrderService stays disabled

Result:
  ✅ Zero impact on trading
  ✅ Confidence visible in dashboards/logs
  ✅ No automatic behavior changes
  ✅ Safe, informational-only
```

### Medium Risk Scenario

```
Confidence populates tomorrow + ConfidenceSignalToOrderService accidentally enabled

Result:
  ⚠️ Automatic trading initiates
  ⚠️ Signal filtering by confidence
  ⚠️ Position sizing changes
  ⚠️ Possible signal rejection
  ⚠️ Behavior change without explicit code change
```

### High Risk Scenario

```
Confidence populates + Auto-trade enabled + Thresholds not reviewed

Result:
  ⚠️ Unexpected trading behavior
  ⚠️ Unknown position sizes
  ⚠️ Signals filtered at unknown threshold
  ⚠️ Could cause trading failures
```

---

**CONFIDENCE ACTIVATION IMPACT REVIEW COMPLETE**

**CRITICAL FINDING: If confidence data begins populating tomorrow, the trading behavior will remain UNCHANGED under current configuration because ConfidenceSignalToOrderService is disabled by default (matchIfMissing=false). However, the infrastructure exists and is ready to activate automatic confidence-based trading if the configuration property `stokr.confidence-strategy.auto-trade-enabled` is set to `true` either accidentally or intentionally. Currently, confidence is 100% telemetry-only. If the auto-trade service activates, it would become DECISION-CRITICAL immediately, filtering signals by threshold and adjusting position sizes based on confidence scores. No code changes would be needed to activate this behavior - only a configuration flag change. This represents a MEDIUM RISK situation where confidence infrastructure is in place but disabled, with potential for accidental activation.**

