# ORPHAN POSITION CLASSIFICATION & RECOVERY DESIGN

**Date:** 2026-06-10  
**Scope:** Design-only, no implementation  
**Purpose:** Robust classification before any automated recovery  

---

## EXECUTIVE SUMMARY

Not every orphaned broker position is an error. Before implementing recovery, we must:

1. **Classify** positions into safe categories
2. **Validate** evidence requirements
3. **Distinguish** manual vs system positions
4. **Refuse** recovery for ambiguous cases
5. **Monitor** continuously without acting automatically
6. **Surface** manual positions prominently in UI

---

## PART A: POSITION CLASSIFICATION FRAMEWORK

### Classification Categories

```
┌─ BROKER_POSITION (exists at Zerodha)
│
├─ [SYSTEM_MANAGED] ✅ (Safe to manage)
│  └─ System created, system owns lifecycle, exits controlled by system
│
├─ [USER_MANUAL] ⚠️ (Observe only)
│  └─ User created, system must never touch, displays as "MANUAL POSITION"
│
├─ [RECOVERABLE_ORPHAN] 🔧 (Can attempt recovery)
│  └─ System created it, evidence exists, recovery can be validated
│
├─ [UNRECOVERABLE_ORPHAN] ❌ (Never touch)
│  └─ System created it, but evidence missing/incomplete, too risky
│
└─ [UNKNOWN_ORIGIN] ❓ (Operator review required)
   └─ Cannot determine origin, must be manually classified
```

---

## PART B: CLASSIFICATION DETECTION RULES

### SYSTEM_MANAGED Positions

**Definition:** Position created by platform and currently tracked in OMS.

**Detection Rules:**

```
Broker Position (qty > 0, symbol = X)
  ↓
Query 1: oms_order WHERE symbol=X AND user_id=trader AND execution_mode=LIVE AND state=ACCEPTED
  │
  ├─ Result: Found ✅
  └─ Confidence: SYSTEM_MANAGED
  
    ↓
Query 2: strategy_signal WHERE symbol=X AND outcome_status IS NULL
  │
  ├─ Result: Found ✅
  └─ Confidence: Confirmed - Active system position
  
    ↓
Query 3: oms_execution WHERE order_id=found_order AND quantity_filled > 0
  │
  ├─ Result: Found ✅
  └─ Confidence: Entry execution recorded
```

**Evidence Checklist:**
- ✅ OMS order exists (state = FILLED or ACCEPTED)
- ✅ Signal exists (outcome_status = NULL for open positions)
- ✅ Execution record exists with matched quantity
- ✅ Timestamps align (signal → order → execution → broker)
- ✅ Entry reference price within 2% of broker entry

**Confidence Level:** HIGH (95%+)

**Classification Decision:** SYSTEM_MANAGED → Safe for system management

---

### USER_MANUAL Positions

**Definition:** Position created outside system (manual trade at broker).

**Detection Rules:**

```
Broker Position (qty > 0, symbol = X)
  ↓
Query 1: oms_order WHERE symbol=X AND user_id=trader AND execution_mode=LIVE
  │
  ├─ Result: NOT Found ❌
  └─ Continue to Query 2
  
Query 2: strategy_signal WHERE symbol=X AND created_at > T-24h
  │
  ├─ Result: NOT Found ❌
  └─ Continue to Query 3
  
Query 3: broker_order_history WHERE symbol=X AND status='EXECUTED' 
         AND placed_by='MANUAL' OR placed_by='WEB'
  │
  ├─ Result: Found ✅
  └─ Confidence: USER_MANUAL
  
Query 4: Verify order placed AFTER last system exit
  │
  ├─ Result: Confirmed ✅
  └─ Confidence: User created AFTER system had no open positions
```

**Evidence Checklist:**
- ✅ NO matching OMS order exists
- ✅ NO matching signal exists
- ✅ Broker order history shows manual placement (WebUI or API, not system)
- ✅ Position timestamp > last system exit timestamp for this symbol
- ✅ Entry price does NOT match any historical system trade

**Confidence Level:** HIGH (90%+) if broker order history available, MEDIUM (70%) if not

**Classification Decision:** USER_MANUAL → NEVER touch, display prominently as "MANUAL POSITION"

---

### RECOVERABLE_ORPHAN Positions

**Definition:** System created but lost OMS record, but sufficient evidence exists to recover.

**Detection Rules:**

```
Broker Position (qty > 0, symbol = X, entry_time=T_entry)
  ↓
Query 1: oms_order WHERE symbol=X AND user_id=trader
  │
  ├─ Result: NOT Found ❌
  └─ Continue to Query 2
  
Query 2: strategy_signal WHERE symbol=X AND created_at BETWEEN (T_entry-5m, T_entry+5m)
  │
  ├─ Result: Found ✅
  ├─ Signal side = ENTRY (not exit)
  ├─ Signal confidence >= 50%
  └─ Continue to Query 3
  
Query 3: Verify broker order from this time period
  │
  ├─ Check: Order timestamp within 1 minute of signal
  ├─ Check: Order quantity matches broker position
  ├─ Check: Order side (BUY/SELL) matches position
  └─ Result: Confirmed ✅
  
Query 4: Check OMS execution timestamps
  │
  ├─ No execution record (data loss scenario)
  ├─ OR execution marked as FAILED but position exists (reconciliation bug)
  └─ Result: Confirmed ✅
```

**Evidence Checklist:**
- ✅ Signal exists within 5-minute window of broker entry
- ✅ Signal is entry signal (not exit)
- ✅ Signal strategy matches broker order notes/tag
- ✅ Broker order timestamp within 2 minutes of signal
- ✅ Broker quantity exactly matches signal quantity
- ✅ Broker side (BUY/SELL) matches signal side
- ✅ NO conflicting evidence (no other signals for same symbol+time)
- ✅ Entry price within 2% of signal reference price

**Evidence Confidence Calculation:**
```
evidenceScore = 0
evidenceScore += 25 if (signal found within 5m window)
evidenceScore += 25 if (broker order quantity matches signal)
evidenceScore += 25 if (broker order side matches signal)
evidenceScore += 15 if (entry price within 2% of signal reference)
evidenceScore += 10 if (no conflicting signals exist)

RECOVERABLE if: evidenceScore >= 85
UNRECOVERABLE if: evidenceScore < 85
```

**Confidence Level:** MEDIUM-HIGH (75-90%) depending on evidence score

**Classification Decision:** RECOVERABLE_ORPHAN → Can attempt recovery IF evidence strong

---

### UNRECOVERABLE_ORPHAN Positions

**Definition:** System likely created but evidence insufficient to safely recover.

**Detection Rules:**

```
Broker Position (qty > 0, symbol = X)
  ↓
Query 1: oms_order WHERE symbol=X
  │
  ├─ Result: NOT Found ❌
  └─ Continue
  
Query 2: strategy_signal WHERE symbol=X AND created_at > T-7d
  │
  ├─ Result: NOT Found ❌
  └─ Continue
  
Query 3: Evidence score < 85%
  │
  ├─ Missing: Signal record
  ├─ Missing: OMS order record
  ├─ Missing: Execution record
  ├─ OR: Timestamps don't align (>5min gap)
  ├─ OR: Quantities don't match
  └─ Result: Insufficient evidence
  
Query 4: Position age check
  │
  ├─ Position > 30 days old with no evidence → VERY HIGH RISK
  └─ Result: Cannot safely recover
```

**Unrecoverability Triggers:**
1. Signal NOT found anywhere
2. All timestamp gaps > 10 minutes
3. Quantity mismatch (broker qty ≠ signal qty)
4. Symbol mismatch or typo
5. Multiple conflicting signals for same symbol at same time
6. Position > 30 days old (stale, may be intentional)
7. Partial fills (broker qty ≠ single execution)

**Confidence Level:** LOW (30-60%) - too risky

**Classification Decision:** UNRECOVERABLE_ORPHAN → NEVER touch, alert operator

---

### UNKNOWN_ORIGIN Positions

**Definition:** Cannot determine origin even after investigation.

**Detection Rules:**

```
Broker Position (qty > 0, symbol = X)
  ↓
All queries inconclusive
  ├─ No clear evidence of USER_MANUAL
  ├─ No clear evidence of SYSTEM_MANAGED
  ├─ No clear evidence of recovery possibility
  └─ Cannot classify with confidence
  
Decision: UNKNOWN_ORIGIN
```

**Trigger Scenarios:**
1. Broker API failures (cannot fetch order history)
2. Database corruption/gaps
3. System under migration
4. Mixed evidence (some signals found, some not)
5. Extremely old positions (>60 days)

**Confidence Level:** UNKNOWN (0-50%)

**Classification Decision:** UNKNOWN_ORIGIN → Operator review required, display prominently

---

## PART C: ENTRY TIME VALIDATION

### Multi-Source Timestamp Alignment

For each orphaned position, validate timestamps across sources:

```
Timeline Validation:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Signal Created
  ↓ (should be within 2 seconds)
OMS Order Created
  ↓ (should be within 1 minute)
OMS Order Dispatched to RabbitMQ
  ↓ (should be within 5 minutes)
Broker Order Placed (Kite)
  ↓ (should be within 30 seconds)
Broker Execution
  ↓ (should be within 5 minutes)
OMS Execution Record Created
```

### Acceptable Time Gaps

| Step | Acceptable Gap | Trigger Level |
|------|---|---|
| Signal → OMS Order | 0-2 seconds | >5s = investigate |
| OMS Order → RabbitMQ | 0-100ms | >500ms = warning |
| RabbitMQ → Broker Submit | 0-5 minutes | >10m = orphan candidate |
| Broker → Execution | 5-30 seconds | >2m = investigate |
| Execution → OMS Record | 0-5 seconds | >10s = investigate |
| **Total Signal → OMS Record** | 0-10 minutes | >30m = orphan |

### Classification Impact

```
IF all timestamps align within tolerance:
    Classification = SYSTEM_MANAGED or RECOVERABLE_ORPHAN
    
IF timestamps have large gaps (>30min):
    Classification = UNRECOVERABLE_ORPHAN or UNKNOWN_ORIGIN
    
IF timestamps are missing entirely:
    Classification = USER_MANUAL (if broker order is manual)
                  or UNKNOWN_ORIGIN (if origin cannot be determined)
```

---

## PART D: MANUAL POSITION PROTECTION RULES

### What System MUST NEVER Do to MANUAL Positions

| Action | Allowed | Reason |
|--------|---------|--------|
| Auto-close | ❌ NO | User owns position, user decides when to close |
| Auto-exit | ❌ NO | Not a system strategy position |
| Attach stop loss | ❌ NO | User may want to hold indefinitely |
| Attach target | ❌ NO | User controls exits |
| Create recovery orders | ❌ NO | Would interfere with user intent |
| Rebalance | ❌ NO | User controls sizing |
| Mark as system position | ❌ NO | Would cause confusion |
| Include in strategy exits | ❌ NO | Not strategy-managed |
| Flatten on kill switch | ⚠️ CONDITIONAL | Only if user explicitly enabled |

### UI Display Requirements for MANUAL Positions

```
┌─────────────────────────────────────────┐
│ 🟡 MANUAL POSITION                      │
├─────────────────────────────────────────┤
│ Symbol:  NSE:JSWSTEEL                   │
│ Quantity: 20 shares                     │
│ Entry Price: ₹715.50                    │
│ Entry Time: 2026-06-10 10:57:28         │
│ Current P&L: +₹310 (+1.8%)              │
│                                         │
│ ⚠️ This is a manual position            │
│ System will NOT manage exits or stops   │
│ Close manually through broker if needed │
│                                         │
│ [Close Position]  [Transfer to System]  │
└─────────────────────────────────────────┘
```

### Rules Enforcement

**System must:**
- ✅ Display "MANUAL POSITION" clearly
- ✅ Use distinct color (yellow/orange warning color)
- ✅ Prevent UI operations that would auto-manage it
- ✅ Log ANY attempt to modify manual positions
- ✅ Alert if position was misclassified
- ✅ Provide "Transfer to System" option (requires signal creation)

---

## PART E: CONTINUOUS MONITORING WORKFLOW

### Orphan Monitor Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│                 SCHEDULED ORPHAN MONITOR                     │
│                  (Every 15 minutes)                          │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
        ┌────────────────────────────┐
        │ Fetch Broker Positions     │
        │ (from BrokerPositionTruth) │
        └────────┬───────────────────┘
                 │
                 ↓
    ┌────────────────────────────────────┐
    │ Compare to OMS ACCEPTED/FILLED      │
    │ Orders (grouped by symbol)          │
    └────────┬───────────────────────────┘
             │
             ↓
    ┌────────────────────────────────────┐
    │ IDENTIFY ORPHANED POSITIONS         │
    │ (Broker qty > 0, no OMS match)      │
    └────────┬───────────────────────────┘
             │
             ├─ No orphans found → DONE
             │
             └─ Orphans found → CLASSIFY
                     │
                     ↓
        ┌─────────────────────────────┐
        │ Run Classification Algorithm │
        │ (Part B above)              │
        └────────┬────────────────────┘
                 │
        ┌────────┴────────┬──────────┬──────────┐
        │                 │          │          │
        ↓                 ↓          ↓          ↓
    SYSTEM_MANAGED    USER_MANUAL  RECOVER-   UNKNOWN
        │                │         ABLE_      │
        │                │         ORPHAN     │
        │                │            │       │
        ↓                ↓            ↓       ↓
    [No Action]    [Flag for UI]  [Alert]  [Alert]
    [Continue      [Display as     [Create  [Require
     tracking]      MANUAL]        task]    review]
                                   [Wait
                                    for
                                    operator]
```

### Alert Workflow for RECOVERABLE_ORPHAN

```
RECOVERABLE_ORPHAN detected
    │
    ├─ Evidence Score >= 85%?
    │
    ├─ YES → Create task in recovery queue
    │        Title: "Recover orphaned NSE:JSWSTEEL (20 qty)"
    │        Evidence: [Signal: SIG123, Confidence: 88%]
    │        Status: PENDING_OPERATOR_APPROVAL
    │        Action options:
    │          1. Auto-recover (recreate OMS record)
    │          2. Manual review first
    │          3. Mark as manual (abandon recovery)
    │
    └─ NO → Create lower-priority alert
             Title: "Uncertain orphan NSE:JSWSTEEL - needs review"
             Evidence score: 72%
             Status: MANUAL_INVESTIGATION_REQUIRED
```

### Alert Workflow for UNRECOVERABLE_ORPHAN

```
UNRECOVERABLE_ORPHAN detected
    │
    ├─ Position age?
    │
    ├─ < 24 hours OLD:
    │   └─ Create MEDIUM priority alert
    │       "Position may be manual or corrupted - review required"
    │
    ├─ 1-7 days OLD:
    │   └─ Create LOW priority alert
    │       "Stale orphan position - user may have abandoned it"
    │
    └─ > 7 days OLD:
        └─ Create INFO level alert
            "Very old orphan - likely intentional manual position"
```

### Alert Workflow for UNKNOWN_ORIGIN

```
UNKNOWN_ORIGIN detected
    │
    ├─ Assessment failed reasons?
    │
    ├─ "Database unavailable":
    │   └─ Auto-retry in 30 minutes
    │       Alert: "Classification postponed due to DB issue"
    │
    ├─ "Ambiguous evidence":
    │   └─ Create HIGH priority alert
    │       "Position origin ambiguous - operator review required"
    │       Suggest: Manual classification
    │
    └─ "System migration/restart":
       └─ Create INFO alert
           "Classification deferred during system restart"
```

---

## PART F: RECOVERY VALIDATION CHECKLIST

### Pre-Recovery Validation

**BEFORE attempting any recovery action:**

```
Recovery Validation Checklist:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

☐ EVIDENCE CHECK
  ☐ Original signal exists?
  ☐ Signal has correct strategy?
  ☐ Signal confidence >= 60%?
  ☐ Signal timestamp aligns (within 5 min)?

☐ ORDER CHECK
  ☐ No OMS order currently exists?
  ☐ Broker order matches signal?
  ☐ Order timestamp aligns?
  ☐ Order quantity matches signal?

☐ EXECUTION CHECK
  ☐ Broker shows filled position?
  ☐ Position quantity exactly matches signal?
  ☐ Position side matches signal?
  ☐ No partial fills (complete execution)?

☐ QUANTITY CHECK
  ☐ Broker qty = Signal qty?
  ☐ Broker qty = Broker order qty?
  ☐ No mismatch > 1%?

☐ SYMBOL CHECK
  ☐ Symbol exactly matches (NSE:JSWSTEEL)?
  ☐ No typos or encoding issues?
  ☐ Symbol is valid trading symbol?

☐ TIMING CHECK
  ☐ All timestamps within acceptable gaps?
  ☐ No gaps > 30 minutes?
  ☐ No future-dated positions?

☐ CONFLICT CHECK
  ☐ No other signals for same symbol at same time?
  ☐ No competing OMS orders?
  ☐ No manual exit events for this symbol?

☐ MANUAL POSITION CHECK
  ☐ Confirmed it's NOT user-manual?
  ☐ No evidence of manual broker entry?
  ☐ Timestamp doesn't match manual web activity?

RESULT: All checks MUST PASS (100% compliance)
        If ANY check FAILS → DO NOT RECOVER
```

### Recovery Actions

**Only if ALL validation checks pass:**

```
Recovery Actions (in order):
━━━━━━━━━━━━━━━━━━━━━━━━━━

Action 1: Create OMS Order Record
  ├─ Fields to populate:
  │  ├─ user_id (from signal)
  │  ├─ signal_id (from signal)
  │  ├─ symbol (from broker position)
  │  ├─ side (from broker position)
  │  ├─ quantity (from broker position)
  │  ├─ state = ACCEPTED (position already filled)
  │  ├─ execution_mode = LIVE
  │  ├─ broker_vendor = ZERODHA
  │  └─ created_at = signal timestamp
  │
  └─ Idempotency key: "recovery:" + uuid
  
Action 2: Create OMS Execution Record
  ├─ Fields to populate:
  │  ├─ order_id (from created OMS order)
  │  ├─ quantity_filled (from broker position)
  │  ├─ price (from broker order)
  │  ├─ execution_status = FILLED
  │  ├─ executed_at = broker order timestamp
  │  └─ broker = "ZERODHA"
  │
  └─ Mark source as "RECOVERY"

Action 3: Update Position References
  ├─ Link strategy instance (if applicable)
  ├─ Update broker position truth snapshot
  └─ Publish position reconciled event

Action 4: Notify System
  ├─ Publish event: "position_recovered"
  ├─ Include: signal_id, order_id, symbol, qty
  ├─ Log: All recovery details for audit
  └─ Alert UI: "Position recovered from broker"
```

---

## PART G: SAFETY RULES - When to REFUSE Recovery

### Absolute Refusal Conditions

**System MUST refuse recovery if ANY of these are true:**

| Condition | Reason | Action |
|-----------|--------|--------|
| Evidence score < 85% | Insufficient confidence | Alert operator, do not recover |
| Signal NOT found | No system record of intent | Assume user-manual, do not recover |
| Quantity mismatch (>1%) | Position size unclear | Alert operator, do not recover |
| Symbol NOT found | Cannot identify asset | Do not recover |
| Multiple conflicting signals | Ambiguous which signal created it | Alert operator, do not recover |
| Position > 30 days old | May be intentional long-term hold | Alert operator, do not recover |
| Timestamp gaps > 30 min | System outage/latency unclear | Alert operator, do not recover |
| User-manual evidence found | User owns this position | DO NOT RECOVER - display as MANUAL |
| Partial fills (qty mismatch) | Cannot guarantee complete execution | Alert operator, do not recover |
| Database inconsistencies | Data integrity issue | STOP, alert engineering |
| Recent signal cancellation | User may have cancelled on purpose | Alert operator, do not recover |
| Kill switch activated | System may be in degraded state | STOP, wait for recovery |
| Broker connection degraded | Cannot trust broker data | DEFER classification |
| OMS modification in progress | Database changing | DEFER classification |

### Operator-Required Review Conditions

**Alert operator for manual intervention if:**

1. Position creates uncertainty in P&L calculations
2. Position affects margin/leverage calculations
3. Position is critical for risk compliance
4. Multiple conflicting interpretations exist
5. Position is very large (>10% of account)
6. Position has unusual entry price (>10% from market)
7. Position created during system downtime
8. Position created during major market volatility

---

## PART H: MONITORING DASHBOARD & UI REQUIREMENTS

### Position Status Indicators

```
SYSTEM_MANAGED (Green)
├─ Icon: ✓ (checkmark)
├─ Color: #2ecc71 (green)
├─ Display: "SYSTEM POSITION"
├─ Allows: Auto-exits, stop losses, system management
└─ Behavior: Normal strategy lifecycle

USER_MANUAL (Yellow)
├─ Icon: ⚠️ (warning)
├─ Color: #f39c12 (orange)
├─ Display: "MANUAL POSITION"
├─ Allows: View only, manual close
└─ Behavior: System will not touch

RECOVERABLE_ORPHAN (Blue)
├─ Icon: 🔧 (wrench)
├─ Color: #3498db (blue)
├─ Display: "RECOVERABLE ORPHAN"
├─ Allows: Operator-approved recovery
└─ Behavior: Awaiting operator action

UNRECOVERABLE_ORPHAN (Red)
├─ Icon: ❌ (cross)
├─ Color: #e74c3c (red)
├─ Display: "UNRECOVERABLE ORPHAN"
├─ Allows: Manual operator review only
└─ Behavior: Requires investigation

UNKNOWN_ORIGIN (Gray)
├─ Icon: ❓ (question)
├─ Color: #95a5a6 (gray)
├─ Display: "UNKNOWN POSITION"
├─ Allows: Manual classification required
└─ Behavior: Requires operator decision
```

### Dashboard View - Positions Page

```
┌──────────────────────────────────────────────────────────┐
│                    POSITIONS DASHBOARD                   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  SYSTEM POSITIONS (4)          MANUAL POSITIONS (2)      │
│  ├─ NSE:ADANIPORTS              ├─ NSE:KOTAKBANK       │
│  ├─ NSE:HCLTECH                 └─ MCX:CRUDEOIL        │
│  ├─ NSE:ICICIBANK                                       │
│  └─ NSE:SBIN                                             │
│                                                          │
│  ORPHANED POSITIONS (2)                                  │
│  ├─ 🔧 NSE:JSWSTEEL (RECOVERABLE)                       │
│  │    Evidence: 88%, Signal found, Ready for recovery   │
│  │    [Approve Recovery]  [Manual Review]  [Mark Manual]│
│  │                                                      │
│  └─ ❌ NSE:ADANIPORTS (UNRECOVERABLE)                   │
│       Evidence: 42%, No matching signal                 │
│       [Manual Review]  [Mark as Manual]  [Abandon]      │
│                                                          │
│  UNKNOWN POSITIONS (1)                                   │
│  ├─ ❓ NSE:OLDSTK                                       │
│      Cannot classify, data ambiguous                     │
│      [Manual Classify]  [Archive]                       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Detail View - Orphan Position

```
┌─────────────────────────────────────────────────────────┐
│  🔧 RECOVERABLE ORPHAN: NSE:JSWSTEEL                   │
├─────────────────────────────────────────────────────────┤
│                                                        │
│ POSITION DETAILS                                       │
│ ├─ Broker Quantity: 20 shares                         │
│ ├─ Entry Price: ₹715.50                              │
│ ├─ Entry Time: 2026-06-10 10:57:28                   │
│ ├─ Current Value: ₹14,310                            │
│ └─ P&L: +₹310 (+2.2%)                                │
│                                                        │
│ EVIDENCE ASSESSMENT (88% Confidence)                  │
│ ✅ Signal found (SIG:abc123def, 73% confidence)      │
│ ✅ Signal timestamp aligns (within 2 minutes)         │
│ ✅ Signal side matches (BUY)                          │
│ ✅ Signal quantity matches (20 shares)                │
│ ✅ Entry price within 2% of reference                │
│ ⚠️  No OMS order record (likely data loss)           │
│ ✅ Broker order timestamp verified                    │
│                                                        │
│ RECOVERY OPTIONS                                       │
│ ┌────────────────────────────────────────┐           │
│ │ [✓] AUTO-RECOVER                       │           │
│ │ Recreate OMS order from signal & broker│           │
│ │ Risk: LOW (evidence score 88%)         │           │
│ │                                        │           │
│ │ [→] MANUAL REVIEW FIRST                │           │
│ │ Show me all evidence before recovery   │           │
│ │                                        │           │
│ │ [!] MARK AS MANUAL                     │           │
│ │ Treat as user-created position         │           │
│ │ System will never touch it             │           │
│ │                                        │           │
│ │ [✕] ABANDON                            │           │
│ │ Do nothing, leave as unknown           │           │
│ └────────────────────────────────────────┘           │
│                                                        │
│ AUDIT TRAIL                                            │
│ └─ Detected: 2026-06-10 12:15 UTC                    │
│    Classification: RECOVERABLE_ORPHAN                 │
│    Evidence collected: 2026-06-10 12:17 UTC           │
│                                                        │
└─────────────────────────────────────────────────────────┘
```

---

## PART I: ANSWERS TO KEY QUESTIONS

### Q1: How to distinguish manual vs system positions?

**Answer:**

1. **Check OMS order history**
   - System position: OMS order exists with matching signal
   - Manual position: NO OMS order found, NO signal found

2. **Check broker order placement method**
   - System position: Placed by platform API (tag contains system-generated ID)
   - Manual position: Placed by user (WEB, API key not platform's, mobile app)

3. **Check signal/execution timeline**
   - System position: Signal → OMS → Broker within 5 minutes
   - Manual position: No corresponding signal record

4. **Check entry reference price alignment**
   - System position: Price within 2% of signal reference price
   - Manual position: Entry price at market at entry time (not correlated to signals)

5. **Check position creation circumstances**
   - System position: Created during regular market hours within strategy parameters
   - Manual position: Created at unusual times or outside strategy symbols

**Decision Rule:**
```
IF (OMS order found) → SYSTEM_MANAGED
ELSE IF (Signal found within 5min window) → Likely system (check evidence)
ELSE IF (Manual broker order history) → USER_MANUAL
ELSE → UNKNOWN_ORIGIN
```

---

### Q2: What evidence is required?

**Answer: Evidence Hierarchy**

**STRONG Evidence (≥90% confidence):**
- OMS order + signal + execution record all present and aligned
- All timestamps within acceptable gaps
- Quantity and price match perfectly
- Strategy signal record clearly indicates system creation

**MEDIUM Evidence (70-89% confidence):**
- Signal found but no OMS order (data loss scenario)
- Evidence score 85+ per formula in Part B
- Broker order matches signal characteristics
- Timeline somewhat misaligned but explainable

**WEAK Evidence (50-69% confidence):**
- Only signal found, no broker confirmation
- Large timestamp gaps (but <30 min)
- Quantity approximately matches (±2%)
- Ambiguous signal source

**NO Evidence (<50% confidence):**
- No signal found
- No broker order history
- Timestamps completely misaligned
- Cannot determine origin

**Minimum Required:** Evidence score ≥ 85 for recovery

---

### Q3: What can be safely auto-recovered?

**Answer:**

**Safely auto-recoverable (no operator approval needed):**
1. RECOVERABLE_ORPHAN with evidence score ≥ 90%
2. AND all validation checks pass
3. AND position < 24 hours old
4. AND signal confidence ≥ 70%
5. AND quantity matches exactly
6. AND no conflicting evidence

**Example:**
```
Signal created: 10:57:20
OMS order would have been: 10:57:25 (gap: 5 seconds)
Broker order placed: 10:57:35 (gap: 15 seconds)
Broker execution: 10:57:42 (gap: 7 seconds)
OMS execution record should be: 10:57:45 (gap: 3 seconds)
Evidence: 92%
Action: AUTO-RECOVER (no approval needed)
```

**Requires operator approval:**
1. Evidence score 85-89%
2. Position 1-7 days old
3. Signal confidence 60-69%
4. Any validation check marked "investigate"

**Example:**
```
Evidence score: 87%
Gap between signal and broker: 4 minutes
Quantity matches but prices slightly off (2.3%)
Position created: 2026-06-09 14:30 (1.5 days old)
Action: CREATE RECOVERY TASK (requires approval)
```

---

### Q4: What must NEVER be auto-recovered?

**Answer: Absolute Refusal List**

**NEVER auto-recover if:**
1. Evidence score < 85%
2. User-manual position (broker history shows manual placement)
3. Signal not found
4. Quantity mismatch > 1%
5. Any validation check FAILS
6. Position > 7 days old
7. Timestamp gap > 30 minutes
8. Multiple conflicting signals exist
9. Position created during system outage (unverifiable)
10. Kill switch was active when position created
11. OMS is under maintenance/migration
12. Broker connection is degraded

**Auto-recover only after:**
- ✅ All validation passes
- ✅ Evidence ≥ 85%
- ✅ No conflicting evidence
- ✅ System is healthy
- ✅ All dependent services available

---

### Q5: What monitoring loop should exist?

**Answer: Continuous Monitoring Workflow**

**Frequency:** Every 15 minutes

**Loop Steps:**
1. Fetch current broker positions (BrokerPositionTruthService)
2. Fetch OMS orders with ACCEPTED or FILLED state
3. Compare → identify orphans (broker qty > 0, no matching OMS order)
4. For each orphan:
   - Run classification algorithm (Part B)
   - Calculate evidence score
   - Create monitoring record
5. For each classification:
   - SYSTEM_MANAGED: No action (tracking continues)
   - USER_MANUAL: Flag for UI, do not auto-manage
   - RECOVERABLE_ORPHAN: Create recovery task, await operator approval
   - UNRECOVERABLE_ORPHAN: Create alert, await operator review
   - UNKNOWN_ORIGIN: Create alert, await operator classification

**Never take automatic action on:**
- Manual positions
- Unrecoverable orphans
- Unknown origin positions

**Operator must explicitly approve:**
- Recovery of RECOVERABLE_ORPHAN
- Classification of UNKNOWN_ORIGIN
- Any modification of existing orphans

---

### Q6: What UI indicators should be shown?

**Answer: Visual Classification Display**

**Position List View:**
```
Color Coding:
- GREEN ✓   = System position (normal)
- YELLOW ⚠️  = Manual position (user-controlled)
- BLUE 🔧   = Recoverable orphan (awaiting approval)
- RED ❌    = Unrecoverable orphan (needs review)
- GRAY ❓   = Unknown (needs classification)

Badges:
Position      | Badge          | Tooltip
———————————————|————————————————|—————————————————————
System        | "SYSTEM"       | "Managed by Stokr"
Manual        | "MANUAL"       | "User created - system will not manage"
Recoverable   | "ORPHAN 88%"   | "Can be recovered (confidence: 88%)"
Unrecoverable | "ORPHAN ❌"    | "Evidence insufficient - needs review"
Unknown       | "UNKNOWN ❓"   | "Origin unclear - operator review needed"
```

**Position Detail View:**
```
Show for MANUAL positions:
- Large yellow banner: "MANUAL POSITION - System will not manage exits"
- Lock icon: Indicates system cannot modify
- Manual-only actions: [Close] [View History]
- System-blocked actions: [Attach Stop Loss] [Set Target] (disabled/grayed)

Show for RECOVERABLE_ORPHAN:
- Recovery task with evidence score
- [✓ Approve] [→ Review First] [! Mark Manual] [✕ Abandon]
- Evidence breakdown (by item)
- Risk assessment

Show for UNRECOVERABLE_ORPHAN:
- "Evidence Insufficient" message
- Evidence score and gaps
- Why recovery is blocked
- [→ Manual Review] [! Mark as Manual] [✕ Abandon]
```

**Dashboard Widget:**
```
POSITION ORPHAN STATUS
├─ Total Positions: 12
├─ System Managed: 8 (66%)
├─ Manual: 2 (17%)
├─ Recoverable: 1 (8%) ← [ACTION NEEDED]
├─ Unrecoverable: 1 (8%)
└─ Unknown: 0 (0%)

[View Orphans]
```

**Alerts:**
```
HIGH: "1 recoverable orphan awaiting recovery approval"
      → Click to view recovery task

MEDIUM: "1 unrecoverable orphan needs investigation"
        → Click to review evidence gaps

LOW: "2 manual positions detected"
     → No action needed, normal operation
```

---

## SUMMARY: Design Decisions Before Implementation

### Decisions Made

1. **Classification is deterministic** - Based on evidence, not assumptions
2. **Manual positions are protected** - System NEVER touches them
3. **Automatic recovery is limited** - Only for high-confidence evidence (≥85%)
4. **Operator approval required** - For anything confidence < 85%
5. **Monitoring is continuous** - But actions are conservative
6. **UI clearly distinguishes types** - Users see what system will/won't do
7. **Validation is comprehensive** - Multi-point checks before recovery
8. **Safety-first approach** - When in doubt, alert operator

### Before Implementation

**Must complete:**
1. ✅ Classification algorithm (Part B) - code implementation
2. ✅ Evidence scoring formula - code implementation
3. ✅ Validation checklist automation - code implementation
4. ✅ UI indicators and dashboards - frontend implementation
5. ✅ Monitoring loop scheduling - async job setup
6. ✅ Operator approval workflow - UI + backend
7. ✅ Recovery action sequence - transactional implementation
8. ✅ Audit logging - complete logging setup

**Testing required:**
1. Classification accuracy against 100+ test positions
2. Evidence scoring on real production orphans
3. Validation checklist with known-good and known-bad positions
4. UI responsiveness and clarity
5. Monitoring loop performance (15-min interval sustainable)
6. Recovery operation idempotency
7. Rollback procedures if recovery fails

**No implementation** of automatic recovery until:
- All designs reviewed and approved
- All tests pass
- Production data validated
- Operator training complete
- Rollback procedures verified

