# IMPLEMENTATION READINESS REVIEW
## Orphan Position Classification & Recovery System

**Date:** 2026-06-10  
**Status:** ⚠️ PARTIAL READINESS - Critical evidence gaps identified  
**Recommendation:** DO NOT IMPLEMENT without addressing blockers  

---

## SECTION 1: EVIDENCE AVAILABILITY MATRIX

### Classification Rules vs Data Availability

| Rule | Required Data | Available? | Source | Confidence | Issue |
|------|---|---|---|---|---|
| SYSTEM_MANAGED: OMS order exists | oms_order.signal_id, state, created_at | ✅ YES | OMS Database | HIGH | None |
| SYSTEM_MANAGED: Signal exists | strategy_signal.id, created_at, symbol | ✅ YES | strategy_signal table | HIGH | None |
| SYSTEM_MANAGED: Execution record | oms_execution.order_id, quantity_filled | ✅ YES | oms_execution table | HIGH | None |
| SYSTEM_MANAGED: Timestamps align | oms_order.created_at vs signal.created_at | ✅ YES | Database | HIGH | None |
| USER_MANUAL: No OMS order | oms_order.signal_id IS NULL | ✅ YES | Database | HIGH | None |
| USER_MANUAL: No matching signal | strategy_signal.symbol, created_at window | ✅ YES | Database | HIGH | None |
| **USER_MANUAL: Manual broker placement** | **broker order placed by WEB/API** | ❌ **NO** | **Zerodha API** | **LOW** | **CRITICAL GAP** |
| **USER_MANUAL: Broker order history** | **Order source (manual vs system)** | ❌ **NO** | **No table exists** | **NONE** | **BLOCKER** |
| **USER_MANUAL: Broker order tags** | **Order tag field from Kite** | ❌ **PARTIAL** | **Not persisted** | **MEDIUM** | **Implementation gap** |
| RECOVERABLE_ORPHAN: Evidence score ≥ 85 | Multiple validation fields | ⚠️ PARTIAL | Multiple sources | MEDIUM | See below |
| Entry price alignment | signal.entry_reference_price vs broker | ✅ YES | signal table | HIGH | None |
| Entry time alignment | signal.created_at vs broker order_timestamp | ⚠️ PARTIAL | See Section 2 | MEDIUM | Timestamps may not be precise |
| Quantity match | oms_order.quantity vs broker position qty | ✅ YES | Database + Broker | HIGH | None |
| Signal side match | strategy_signal.side vs broker transaction_type | ✅ YES | Database + Broker | HIGH | None |

### Critical Findings

**SYSTEM_MANAGED Detection: ✅ FULLY POSSIBLE**
- OMS order exists + signal exists + execution exists
- All database tables present
- Confidence: 95%+

**USER_MANUAL Detection: ⚠️ SEVERELY LIMITED**
- Can prove "no OMS order + no signal" = ✅ can do this
- Can infer "likely manual" based on timeline
- **BUT CANNOT PROVE** order was placed by user vs system
- **Reason:** Zerodha API does NOT return order source/placement method
- **Reason:** No broker_order_history table to store order metadata

**RECOVERABLE_ORPHAN: ⚠️ FEASIBLE WITH WORKAROUNDS**
- Signal exists within time window: ✅ YES
- Timestamps align: ⚠️ PARTIAL (see Section 2)
- Quantity/side match: ✅ YES
- Evidence score: ⚠️ CAN CALCULATE (but missing some data points)

---

## SECTION 2: BROKER REALITY VALIDATION

### Zerodha Kite API - Order Data Availability

**What Zerodha PROVIDES when fetching orders:**

```
Field Name              | Returned | Parseable | Used | Comment
———————————————————————|———————————|———————————|———————|—————————————————————
order_id               | ✅ YES   | ✅ YES   | ✅   | Kite order ID
parent_order_id        | ✅ YES   | ✅ YES   | ❌   | For bracket/cover orders
exchange               | ✅ YES   | ✅ YES   | ✅   | NSE/BSE/MCX
tradingsymbol          | ✅ YES   | ✅ YES   | ✅   | Symbol
transaction_type       | ✅ YES   | ✅ YES   | ✅   | BUY/SELL
product                | ✅ YES   | ✅ YES   | ✅   | MIS/NRML/CNC
variety                | ✅ YES   | ✅ YES   | ❌   | regular/bo/co
order_type             | ✅ YES   | ✅ YES   | ✅   | MARKET/LIMIT/SL/SL-M
quantity               | ✅ YES   | ✅ YES   | ✅   | Order qty
price                  | ✅ YES   | ✅ YES   | ✅   | Limit price
status                 | ✅ YES   | ✅ YES   | ✅   | OPEN/COMPLETE/CANCELLED
order_timestamp        | ✅ YES   | ✅ YES   | ✅   | When order was placed
status_message         | ✅ YES   | ✅ YES   | ❌   | Error message if rejected
filled_quantity        | ✅ YES   | ✅ YES   | ✅   | Qty executed
average_price          | ✅ YES   | ✅ YES   | ✅   | Fill price
——————————————————————|———————————|———————————|———————|—————————————————————
tag                    | ✅ YES   | ✅ YES   | ❌   | **NOT PERSISTED** |
placed_by              | ❌ NO    | ❌ NO    | ❌   | **DOES NOT EXIST** |
placement_source       | ❌ NO    | ❌ NO    | ❌   | **DOES NOT EXIST** |
placement_method       | ❌ NO    | ❌ NO    | ❌   | **DOES NOT EXIST** |
client_order_id        | ❌ NO    | ❌ NO    | ❌   | **DOES NOT EXIST** |
———————————————————————|———————————|———————————|———————|—————————————————————
```

### Critical Broker Data Gaps

| Feature | Available | Reliable | Missing | Impact |
|---------|-----------|----------|---------|--------|
| Order placement source (manual vs API) | ❌ NO | N/A | YES | **BLOCKS USER_MANUAL detection** |
| Order modification history | ❌ NO | N/A | YES | Cannot track order changes |
| Order cancellation history | ❌ NO | N/A | YES | Cannot determine if user cancelled |
| Placement method (web/app/API) | ❌ NO | N/A | YES | **BLOCKS USER_MANUAL classification** |
| Order tags/notes | ✅ Partial | ⚠️ SOMETIMES | YES | **Not persisted in our system** |
| Placement IP address | ❌ NO | N/A | YES | Cannot determine user vs system |
| User agent / device info | ❌ NO | N/A | YES | Cannot determine manual entry |
| API key used for order | ❌ NO | N/A | YES | **Cannot verify system vs manual API** |

### What We CAN Use to Infer Manual Orders

**Workaround Evidence (if ALL conditions met):**
1. ❌ No OMS order found in database
2. ❌ No matching signal found in database
3. ✅ Position exists at broker
4. ✅ Order timestamp > last system exit for this symbol
5. ✅ Order price does NOT correlate to any historical signal

**Confidence Level:** MEDIUM (70%) - Inferred, not proven

---

## SECTION 3: CLASSIFICATION FEASIBILITY ASSESSMENT

### PROVEN_SYSTEM Classification

**Feasibility: ✅ FULLY FEASIBLE**

**Evidence Required:**
```
✅ OMS order found with signal_id
✅ Strategy signal found
✅ OMS execution found
✅ Timestamps align (signal → order → broker within 5 min)
✅ All quantities match
```

**Proof Method:** Multi-table JOIN verification
**Assumption Count:** 0 (fully proven)
**Risk Level:** VERY LOW (95%+ confidence achievable)

**Database Query Feasible:** YES
```sql
SELECT bp.* FROM broker_position bp
WHERE NOT EXISTS (
  SELECT 1 FROM oms_order oo 
  WHERE oo.user_id = bp.user_id
  AND oo.symbol = bp.symbol
  AND oo.state IN ('ACCEPTED', 'FILLED')
)
-- If no results found → PROVEN MANUAL or ORPHAN
```

---

### PROVEN_MANUAL Classification

**Feasibility: ❌ INFEASIBLE (with current data)**

**Evidence Required:**
```
❌ Order placement source (MISSING)
❌ Manual broker placement confirmed (MISSING)
❌ Order tags indicating manual (NOT PERSISTED)
```

**Why NOT Feasible:**
1. Zerodha API does NOT return "placed_by" field
2. No broker_order_history table to store order tags
3. Cannot distinguish API call (system) vs web click (user)
4. Both system and user could use same API key

**Current Workaround:** Infer from negative evidence
- No OMS order + No signal + Entry timestamp after last exit
- **Confidence:** 70% (could still be system error)

**Risk:** HIGH
- Would misclassify system orphans as USER_MANUAL
- Would prevent recovery of legitimate system positions

**Recommendation:** 
**Cannot safely implement PROVEN_MANUAL without additional data**

---

### LIKELY_SYSTEM Classification

**Feasibility: ✅ MOSTLY FEASIBLE**

**Evidence:** Signal exists, timestamps align, quantities match, but OMS record missing

**Proof Method:** Time-window correlation
```
Signal within 5 minutes of broker order timestamp
+ Order side matches signal side
+ Order quantity matches signal quantity
+ Entry price within 2% of signal reference price
+ Evidence score ≥ 85%
```

**Assumptions:**
1. Signal timestamps are reliable (assume YES - system-generated)
2. Broker order timestamps are reliable (assume YES - from Kite API)
3. Time window (5 min) is correct (could vary with network latency)
4. No duplicate signals exist (need to verify)

**Assumption Count:** 3 (MODERATE)
**Risk Level:** MEDIUM (75-85% confidence achievable)

**Known Blockers:**
- Large timestamp gaps (>5 min) = uncertain
- Signal timestamp precision (seconds vs minutes)
- Broker API latency variations
- Network outages during execution

**Database Query Feasible:** YES

---

### LIKELY_MANUAL Classification

**Feasibility: ⚠️ PARTIALLY FEASIBLE**

**Evidence:** No OMS order, no signal, but position exists

**Proof Method:** Negative correlation
```
Signal NOT found within 30-min window
+ OMS order NOT found
+ Position entry timestamp > all recent system signals
+ Position NOT correlated to any historical signal
```

**Assumptions:**
1. All signals are captured in database (UNCERTAIN)
2. Signal creation timestamp is accurate (ASSUME YES)
3. No signal deletion occurs (UNCERTAIN - need to check soft deletes)
4. No batch signal creation delays (UNCERTAIN)

**Assumption Count:** 4 (HIGH)
**Risk Level:** HIGH (50-70% confidence only)

**Known Blockers:**
- Signal failures during creation (would not be recorded)
- Batch signal processing delays (signal recorded late)
- Soft deletes in strategy_signal table (deleted signals hidden)
- System restarts during order execution

**Database Query Feasible:** YES (but results uncertain)

---

### UNKNOWN Classification

**Feasibility: ✅ FULLY FEASIBLE**

**Evidence:** Cannot make any determination

**Proof Method:** Negative result from all other classifications

**Assumptions:** 0 (by definition)
**Risk Level:** VERY LOW

**Database Query Feasible:** YES

---

## SECTION 4: RECOVERY FEASIBILITY ASSESSMENT

### Recovery Validation Step Feasibility

| Validation Step | Can Gather? | Evidence Retained? | Already Persisted? | Blocker |
|---|---|---|---|---|
| Signal exists within time window | ✅ YES | ✅ YES | ✅ YES | None |
| Signal strategy matches | ✅ YES | ✅ YES | ✅ YES | None |
| OMS order does NOT exist | ✅ YES | ✅ YES | ✅ YES | None |
| Broker order matches signal | ✅ YES | ⚠️ PARTIAL | ✅ YES | May need broker API call |
| Broker order timestamp | ✅ YES | ✅ YES | ❌ NO | Must fetch from Zerodha each time |
| Execution quantity | ✅ YES | ✅ YES | ✅ YES | None |
| Execution price | ✅ YES | ✅ YES | ✅ YES | None |
| No conflicting signals | ✅ YES | ✅ YES | ✅ YES | None |
| Entry price alignment | ✅ YES | ✅ YES | ✅ YES | None |
| No partial fills | ✅ YES | ✅ YES | ✅ YES | None |
| **User manual confirmation** | ❌ NO | ❌ NO | ❌ NO | **BLOCKER** |
| **Broker order source** | ❌ NO | ❌ NO | ❌ NO | **BLOCKER** |
| **Order tag verification** | ⚠️ MUST FETCH | ⚠️ PARTIAL | ❌ NO | **DATA LOSS** |

### Critical Recovery Blockers

**BLOCKER 1: Broker Order Tag Not Persisted**

When system places order, we SET tag = clientOrderId (last 20 chars).
Example: Tag = "3333-333333333333"

**Problem:** When we fetch orders from Zerodha, we don't STORE the tag.

**Current Code Flow:**
```
System places order → Tag is set in Kite API ✅
Later, system fetches orders from Kite → Tag is returned ✅
We parse tag → BUT DON'T PERSIST IT ❌
Next day, we fetch again → Tag is there (if order not cancelled)
But if order is older, tag might be lost or not accessible
```

**Impact:** Cannot definitively prove order was placed by system if we only check OMS table.

**Workaround:** Query Zerodha API every time to fetch and check tag
- **Cost:** API call per position (rate limits?)
- **Latency:** ~500ms per position
- **Reliability:** Depends on Zerodha API availability

---

**BLOCKER 2: Broker Order History Not Available**

**Problem:** Zerodha API only returns CURRENT orders, not historical.

If order is cancelled or completed:
- Not returned by getOrders() API
- No history available
- Cannot check if it was manual or system

**Scenario:** System placed order → Position filled → Order completed
Later: Position is orphaned, but order is no longer visible in Zerodha

**Impact:** CANNOT recover if original broker order is no longer accessible

**Workaround:** Would need to fetch and persist order history immediately
- Requires new table: broker_order_history
- Must be populated whenever we fetch orders
- Historical data will be missing for all existing orders

---

**BLOCKER 3: Broker Order Source Not Available**

**Problem:** Zerodha Kite API DOES NOT provide "placed_by" or "source" field

**Attempt 1:** Use tag field to identify system orders
- ⚠️ Only works if tag is set consistently
- ⚠️ Manual orders have no tag
- ⚠️ But missing tag doesn't prove manual origin

**Attempt 2:** Check if API credentials match system's API
- ❌ Cannot do this - all Zerodha orders use same API credentials
- System orders and user API orders use same key

**Attempt 3:** Use IP address or user agent
- ❌ Not available from Kite API
- Would require separate audit logging

**Impact:** Cannot definitively distinguish manual vs system orders for most positions

---

## SECTION 5: FAILURE SCENARIOS & RISK ASSESSMENT

### Failure Scenario Analysis

| Scenario | Likelihood | Classification Risk | Recovery Risk | Consequence |
|----------|-----------|--|--|---|
| **Restart during entry execution** | HIGH | ORPHAN misclassified as MANUAL (20%) | HIGH | Position not recovered; user thinks it's manual |
| **Broker delay (order slow to appear)** | MEDIUM | ORPHAN misclassified as UNKNOWN (15%) | MEDIUM | Awaits manual review unnecessarily |
| **OMS delay (signal-order gap >5min)** | MEDIUM | LIKELY_SYSTEM score drops (5-10%) | MEDIUM | Below recovery threshold; manual intervention needed |
| **Signal persistence failure** | LOW | ORPHAN misclassified as MANUAL (30%) | VERY HIGH | Would prevent recovery of legitimate position |
| **Reconciliation failure (execution not recorded)** | LOW | LIKELY_SYSTEM becomes UNRECOVERABLE (20%) | HIGH | Cannot recover due to missing execution record |
| **Manual trade matching system signal** | MEDIUM | USER_MANUAL misclassified as SYSTEM (25%) | CRITICAL | System would auto-manage user's manual position |
| **Duplicate signals created** | LOW | LIKELY_SYSTEM becomes UNRECOVERABLE (10%) | MEDIUM | Ambiguous - which signal created position? |
| **Stale position (>30 days old)** | LOW | Correctly classified as UNRECOVERABLE (95%) | LOW | Safe - operator review required |
| **Order cancelled manually, position remains** | MEDIUM | LIKELY_SYSTEM (score 60%) | HIGH | Below threshold; manual review needed |
| **Tag field corrupted or lost** | LOW | PROVEN_SYSTEM becomes LIKELY (15%) | MEDIUM | Less confidence but still recoverable |
| **Zerodha API returns wrong tag** | VERY LOW | PROVEN_SYSTEM false negative (1%) | LOW | Unlikely; trust Kite API |
| **Multiple fills across days** | MEDIUM | Timestamp alignment fails; UNRECOVERABLE | HIGH | Position not recovered |
| **Broker order placed, Zerodha API fails to return it** | LOW | Position appears UNKNOWN | LOW | Would be in broker but not detected by system |

### Risk Assessment by Classification

**PROVEN_SYSTEM Recovery: VERY LOW RISK** ✅
- Clear evidence
- No false positives expected
- Safe to auto-recover

**LIKELY_SYSTEM Recovery: MEDIUM-HIGH RISK** ⚠️
- Depends on time window (5 min correct?)
- Failure scenario: Restart during entry → marked as USER_MANUAL
- **Recommendation:** Require operator approval for evidence <90%

**LIKELY_MANUAL Handling: HIGH RISK** ⚠️
- Could misclassify ORPHAN as MANUAL
- User might think position is theirs when it's system's
- **Recommendation:** Do NOT auto-recover as MANUAL; only flag for review

**USER_MANUAL Display: CRITICAL RISK IF WRONG** ⚠️
- If we misclassify SYSTEM position as MANUAL, system won't manage exits
- Position may accumulate losses
- **Recommendation:** High confidence bar (90%+) required before flagging as MANUAL

---

## SECTION 6: PRODUCTION READINESS SCORING

### Classification System

| Dimension | READY | PARTIAL | NOT READY | Current | Issues |
|-----------|-------|---------|-----------|---------|--------|
| **Classification Algorithm** | All rules proven | Some rules inferred | Can't classify | PARTIAL | PROVEN_SYSTEM ✅, Others ⚠️ |
| **Evidence Availability** | All data present | Most data present | Key data missing | PARTIAL | Tag not persisted, no order history |
| **Broker Data** | Full metadata | Partial metadata | No distinguishing field | PARTIAL | No "placed_by" field in Kite |
| **Database Schema** | All tables present | Tables mostly present | Missing tables | PARTIAL | No broker_order_history table |
| **Monitoring Loop** | Can run every 15 min | Can run but slow | Cannot run | NOT READY | Feasible but needs optimization |
| **Recovery Validation** | All checks possible | Most checks possible | Critical checks missing | PARTIAL | Cannot prove manual origin |
| **Manual Position Protection** | Cannot misclassify | Might misclassify | Cannot distinguish | NOT READY | No way to prove manual origin |
| **UI Display** | All indicators needed | Most indicators | Key indicators missing | PARTIAL | Classification confidence varies |
| **Auditability** | Full audit trail | Partial audit trail | No trail | NOT READY | No broker order history log |
| **Failure Handling** | All scenarios safe | Some risky scenarios | Dangerous scenarios | PARTIAL | Risk of misclassifying system as manual |

---

### Readiness Grades

#### Classification
- **PROVEN_SYSTEM:** READY ✅
- **LIKELY_SYSTEM:** PARTIAL ⚠️ (depends on time window assumptions)
- **USER_MANUAL:** NOT READY ❌ (cannot prove origin)
- **LIKELY_MANUAL:** PARTIAL ⚠️ (high inference, low proof)
- **UNKNOWN:** READY ✅

**Overall Classification Grade:** **PARTIAL**

#### Monitoring
- **Loop scheduling:** READY ✅
- **Orphan detection:** READY ✅
- **Classification for each:** PARTIAL ⚠️
- **Alert generation:** READY ✅

**Overall Monitoring Grade:** **READY** ✅

#### Recovery
- **Validation checks:** PARTIAL ⚠️
- **Evidence gathering:** PARTIAL ⚠️
- **Safety checks:** PARTIAL ⚠️
- **Broker order verification:** NOT READY ❌

**Overall Recovery Grade:** **PARTIAL**

#### Manual Position Protection
- **Detection of manual positions:** NOT READY ❌
- **Prevention of system interference:** NOT READY ❌
- **UI display:** PARTIAL ⚠️

**Overall Manual Protection Grade:** **NOT READY** ❌

#### UI
- **Color coding system:** READY ✅
- **Detail views:** READY ✅
- **Confidence display:** PARTIAL ⚠️
- **Manual position warnings:** PARTIAL ⚠️

**Overall UI Grade:** **PARTIAL**

#### Auditability
- **Classification logs:** PARTIAL ⚠️
- **Recovery decision logs:** PARTIAL ⚠️
- **Order history trail:** NOT READY ❌
- **Evidence snapshot logs:** PARTIAL ⚠️

**Overall Auditability Grade:** **PARTIAL**

---

## SECTION 7: FINAL GATE DECISION

### Implementation Safety Assessment

**Question A:** Safe to implement now?

**Answer: NO - DO NOT IMPLEMENT**

---

### Question B: More evidence required before implementation?

**Answer: YES - CRITICAL EVIDENCE GAPS IDENTIFIED**

---

### Exact Missing Evidence

#### CRITICAL BLOCKERS (Must fix before implementation)

**BLOCKER 1: Cannot distinguish manual vs system orders**
- **Missing:** Field in Zerodha API indicating order source
- **Missing:** broker_order_history table to persist order tags
- **Impact:** Cannot reliably classify USER_MANUAL positions
- **Fix Required:** 
  - [ ] Create broker_order_history table to store order metadata
  - [ ] Add migration to start persisting order tags when fetching from Zerodha
  - [ ] Backfill broker_order_history with last 30 days of orders
  - [ ] Add field: order_source (SYSTEM, MANUAL, UNKNOWN)
  - [ ] Add field: order_tag (to match system orders)
  - [ ] Verify tag persistence works for 90+ days

**BLOCKER 2: No broker order history when orders complete**
- **Missing:** Historical order data after orders are executed/cancelled
- **Missing:** Cannot verify order origin for old positions
- **Impact:** Cannot recover positions > 1-2 days old
- **Fix Required:**
  - [ ] Implement immediate order fetching after placement confirmation
  - [ ] Store order metadata in broker_order_history BEFORE order completes
  - [ ] Create snapshot of broker_order_history after order fill
  - [ ] Ensure tag and metadata are captured within seconds of fill

**BLOCKER 3: No way to prove system origin after broker data expires**
- **Missing:** Tag persistence for > 30 days
- **Missing:** Order metadata audit trail
- **Impact:** Cannot recover positions > 30 days old with confidence
- **Fix Required:**
  - [ ] Verify Zerodha API returns tags for 30+ day old orders
  - [ ] If not, must store tag in our database immediately at order creation
  - [ ] Create table: system_order_registry with clientOrderId → signalId mapping
  - [ ] Backfill registry with last 3 months of orders

---

#### MAJOR CONCERNS (Should fix before implementation)

**CONCERN 1: Time window assumptions**
- **Assumption:** Signal → Order → Broker within 5 minutes
- **Risk:** Network delays, system restarts could exceed this
- **Evidence:** No data on actual execution times in production
- **Fix Required:**
  - [ ] Analyze actual execution timelines from logs
  - [ ] Determine appropriate time window (5min? 10min? 30min?)
  - [ ] Update evidence scoring formula based on real data
  - [ ] Log all execution timeline data for future analysis

**CONCERN 2: Signal creation reliability**
- **Assumption:** All signals are captured in strategy_signal table
- **Risk:** Signal creation failures would cause misclassification
- **Evidence:** No audit of signal creation success rate
- **Fix Required:**
  - [ ] Analyze strategy_signal creation logs
  - [ ] Verify signal persistence success rate (should be 99%+)
  - [ ] Identify any known failure modes
  - [ ] Add monitoring for signal creation failures

**CONCERN 3: Soft delete behavior**
- **Assumption:** strategy_signal soft deletes won't hide relevant signals
- **Risk:** Deleted signals not found → orphan misclassified as MANUAL
- **Evidence:** No audit of signal soft delete patterns
- **Fix Required:**
  - [ ] Check if signals are ever soft-deleted
  - [ ] Update queries to check deleted flag appropriately
  - [ ] Document soft delete retention policy

---

#### RECOMMENDED ENHANCEMENTS (Fix after core blockers)

**ENHANCEMENT 1: Order tag verification** 
- **Current:** Tag is set but not persisted
- **Recommended:** Parse and store tag field from broker API
- **Timeline:** After BLOCKER 1 fix

**ENHANCEMENT 2: Execution timeline metrics**
- **Current:** No tracking of actual execution times
- **Recommended:** Log signal→order→broker timestamp gaps
- **Timeline:** Before classification rules go live

**ENHANCEMENT 3: Broker data audit trail**
- **Current:** No persistent record of broker order fetches
- **Recommended:** Store broker_order_snapshot table
- **Timeline:** Phase 2 (after core implementation)

---

## IMPLEMENTATION GATE CHECKLIST

**Before coding ANY recovery system:**

- [ ] BLOCKER 1 FIXED: broker_order_history table created
- [ ] BLOCKER 1 FIXED: Order tags being persisted
- [ ] BLOCKER 1 FIXED: Backfill complete for historical orders
- [ ] BLOCKER 2 FIXED: Order metadata captured at placement
- [ ] BLOCKER 2 FIXED: Order metadata captured at fill
- [ ] BLOCKER 3 FIXED: system_order_registry created
- [ ] BLOCKER 3 FIXED: Historical registry backfilled
- [ ] CONCERN 1 RESOLVED: Actual execution timelines analyzed
- [ ] CONCERN 1 RESOLVED: Time window evidence scoring updated
- [ ] CONCERN 2 RESOLVED: Signal creation reliability verified
- [ ] CONCERN 3 RESOLVED: Soft delete behavior documented
- [ ] TESTING PLAN: Unit tests for each classification rule
- [ ] TESTING PLAN: Integration tests with production orphans
- [ ] TESTING PLAN: Failure scenario tests (restart, delays, etc.)
- [ ] OPERATOR TRAINING: Classification confidence explanation
- [ ] OPERATOR TRAINING: Recovery approval workflow
- [ ] ROLLBACK PROCEDURE: Plan to undo classification if wrong

---

## SUMMARY

### Safe to Implement Now?

**🔴 NO - DO NOT IMPLEMENT**

### Why?

1. **Cannot reliably identify manual vs system orders** - Zerodha API doesn't provide order source
2. **Broker order history not persisted** - No way to verify order origin after execution
3. **User manual positions at risk** - Could misclassify system orders as manual
4. **Recovery validation incomplete** - Cannot verify "broker order matches signal" reliably
5. **Evidence scoring incomplete** - Time window assumptions not validated

### Critical Path to Readiness

1. **First:** Create broker_order_history table + persist tags (WEEK 1)
2. **Second:** Create system_order_registry for clientOrderId mapping (WEEK 1)
3. **Third:** Analyze execution timelines from production logs (WEEK 2)
4. **Fourth:** Implement and test classification rules (WEEK 2-3)
5. **Fifth:** Operator training and approval workflow (WEEK 3)
6. **Finally:** Deploy monitoring (no recovery) first, monitoring+recovery second

### Minimum Viable Implementation

If forced to implement NOW (NOT RECOMMENDED):

1. **ONLY deploy PROVEN_SYSTEM classification** (high confidence)
2. **ONLY deploy monitoring** (no auto-recovery)
3. **ONLY support operator-approved recovery**
4. **NEVER auto-classify as USER_MANUAL**
5. **ALWAYS flag uncertain orphans for manual review**
6. **Plan Phase 2** for reliable manual position detection

---

## CONCLUSION

The classification design is **well-architected but premature**.

**The system cannot be safely implemented without:**
- Fixing broker order history gap
- Validating execution timeline assumptions
- Ensuring manual position protection

**Proceed with engineering work ONLY after:** 
- Addressing all BLOCKER items
- Resolving all CONCERN items
- Completing recommended enhancements

**Estimated time to readiness:** 3-4 weeks
