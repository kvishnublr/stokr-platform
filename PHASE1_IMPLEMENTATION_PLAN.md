# PHASE 1 IMPLEMENTATION PLAN
## Orphan Position Monitoring & Classification (No Recovery)

**Date:** 2026-06-10  
**Scope:** Monitoring, classification, operator review only  
**NO Recovery Actions** (Phase 2)  
**Status:** Design-only, awaiting approval before coding  

---

## EXECUTIVE SUMMARY

Phase 1 builds the foundation:
- ✅ Detect orphaned positions continuously
- ✅ Classify them (SYSTEM/MANUAL/RECOVERABLE/UNRECOVERABLE/UNKNOWN)
- ✅ Score evidence strength
- ✅ Surface to operators for review
- ❌ NO automatic actions
- ❌ NO position modifications
- ❌ NO OMS order creation

Every orphan stays in REVIEW_REQUIRED state until:
1. Operator explicitly approves classification
2. Production evidence supports the classification
3. Phase 2 (recovery) is implemented with full safety gates

---

## PART A: SERVICES TO CREATE

### Service 1: OrphanPositionDetectionService

**Purpose:** Detect positions that exist at broker but have no OMS record

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public List<DetectedOrphanPosition> scanForOrphans(UUID userId)
  // Input: User ID
  // Process:
  //   1. Fetch broker positions via BrokerPositionTruthService
  //   2. Fetch OMS ACCEPTED/FILLED orders for user
  //   3. Compare by symbol
  //   4. Return positions with broker qty > 0 but no matching OMS order
  // Output: List<DetectedOrphanPosition>
  // Frequency: Called by OrphanMonitorScheduler every 15 minutes

public List<DetectedOrphanPosition> scanAllOrphans()
  // Input: None
  // Process: Scan all active traders
  // Output: All orphaned positions across platform
  // Frequency: Full scan once per hour (off-peak)

public void recordOrphanDetection(DetectedOrphanPosition orphan)
  // Persist detection to orphan_position_history table
  // Track: timestamp, user_id, symbol, quantity, broker_entry_time
  // Used for audit trail

private List<DetectedOrphanPosition> identifyOrphans(
    List<BrokerPosition> brokerPositions,
    List<OmsOrder> activeOrders
  )
  // Core logic: Orphan = broker position with no matching OMS order
  // Matching rules:
  //   - symbol match
  //   - user_id match
  //   - order state IN (ACCEPTED, FILLED)
  //   - execution_mode = LIVE
```

**Dependencies:**
- BrokerPositionTruthService (fetch broker positions)
- OmsOrderRepository (fetch OMS orders)
- OrphanPositionRepository (persist detections)

**Error Handling:**
- Broker API failures → Log, skip scan, try again in 15min
- Database errors → Log, alert operator, fail fast
- Empty results → Normal, log as "no orphans detected"

---

### Service 2: OrphanClassificationService

**Purpose:** Classify each orphan into one of 5 categories

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public OrphanClassification classify(DetectedOrphanPosition orphan)
  // Input: Orphan position
  // Process: Run classification algorithm (see Part B)
  // Output: OrphanClassification with:
  //   - classification type (SYSTEM/MANUAL/RECOVERABLE/UNRECOVERABLE/UNKNOWN)
  //   - evidence score (0-100)
  //   - confidence level (PROVEN/LIKELY/INFERRED/UNKNOWN)
  //   - supporting evidence list
  //   - blocking issues list
  // Frequency: Called immediately after orphan detection

private ClassificationResult classifyAsSystemManaged(DetectedOrphanPosition orphan)
  // Rule: OMS order exists + signal exists + execution exists
  // Evidence required:
  //   - oms_order.signal_id matches found signal
  //   - oms_execution found with matching quantity
  //   - All timestamps align
  // Return: ClassificationResult with PROVEN confidence

private ClassificationResult classifyAsLikelySystem(DetectedOrphanPosition orphan)
  // Rule: Signal found but OMS order missing (likely data loss)
  // Evidence required:
  //   - Signal within 5-min window of broker order
  //   - Signal side matches broker transaction_type
  //   - Signal quantity matches broker position
  //   - Evidence score ≥ 85%
  // Return: ClassificationResult with LIKELY confidence

private ClassificationResult classifyAsRecoverableOrphan(DetectedOrphanPosition orphan)
  // Rule: Evidence strong enough for Phase 2 recovery
  // Same as LIKELY_SYSTEM but operator can trigger recovery
  // Confidence ≥ 85%, position < 7 days old, no conflicting signals
  // Return: ClassificationResult with RECOVERABLE flag

private ClassificationResult classifyAsUnrecoverableOrphan(DetectedOrphanPosition orphan)
  // Rule: Evidence insufficient or conflicting
  // No signal found, or timestamps don't align, or ambiguous
  // Return: ClassificationResult with low confidence

private ClassificationResult classifyAsUnknown(DetectedOrphanPosition orphan)
  // Rule: Cannot determine origin
  // Database failures, ambiguous evidence, stale position
  // Return: ClassificationResult with UNKNOWN confidence
```

**Dependencies:**
- StrategySignalRepository (find matching signal)
- OmsExecutionRepository (find matching execution)
- EvidenceScoringService (calculate confidence score)

**Output:** OrphanClassification entity persisted to orphan_classification_history

---

### Service 3: EvidenceScoringService

**Purpose:** Calculate confidence score for each classification

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public EvidenceScore calculateScore(DetectedOrphanPosition orphan)
  // Input: Orphan position
  // Process: Run scoring algorithm (see Part B.2)
  // Output: EvidenceScore with:
  //   - score (0-100)
  //   - breakdown by evidence type
  //   - passing evidence list
  //   - blocking issues list
  // Formula: See Part B.2

private int scoreSignalAlignment(StrategySignalEntity signal, DetectedOrphanPosition orphan)
  // Score: 0-25 points
  // Criteria:
  //   - Signal exists within 5-min window: +25
  //   - Signal within 10-min window: +20
  //   - Signal within 30-min window: +10
  //   - Signal found but >30 min away: +0
  //   - Signal not found: -10 (blocker)

private int scoreQuantityMatch(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-25 points
  // Criteria:
  //   - Exact match: +25
  //   - Within 1%: +20
  //   - Within 5%: +10
  //   - Mismatch >5%: -20 (blocker)

private int scoreSideMatch(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-25 points
  // Criteria:
  //   - BUY=BUY or SELL=SELL: +25
  //   - Mismatch: -25 (blocker)

private int scorePriceAlignment(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-15 points
  // Criteria:
  //   - Entry price within 1% of signal reference: +15
  //   - Within 2%: +10
  //   - Within 5%: +5
  //   - >5% mismatch: +0

private int scoreConflictingSignals(DetectedOrphanPosition orphan)
  // Score: 0-10 points
  // Criteria:
  //   - No conflicting signals: +10
  //   - One conflicting signal: +5
  //   - Multiple conflicting signals: -10 (blocker)

// BLOCKING CONDITIONS (fail scoring immediately):
// - timestamp gaps > 30 minutes
// - quantity mismatch > 5%
// - side mismatch
// - multiple conflicting signals
// - position > 30 days old
```

**Dependencies:**
- StrategySignalRepository
- OmsOrderRepository
- OmsExecutionRepository

**Output:** EvidenceScore entity for audit trail

---

### Service 4: OperatorReviewWorkflowService

**Purpose:** Manage operator review and classification approval process

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public OrphanReviewTask createReviewTask(OrphanClassification classification)
  // Create operator task for review
  // Status: PENDING_REVIEW
  // Assigned to: Operator (or broadcast if no assignee)
  // Due: 24 hours
  // Return: OrphanReviewTask ID

public void assignReviewTask(UUID taskId, UUID operatorId)
  // Assign task to specific operator
  // Log: Assignment event
  // Notify: Operator via in-app notification

public OrphanReviewApproval approveClassification(
    UUID taskId,
    UUID operatorId,
    String classification,
    String notes
  )
  // Input: Task ID, operator, approved classification, notes
  // Validation:
  //   - Task exists and is PENDING_REVIEW
  //   - Operator is authorized
  //   - Classification is one of: SYSTEM/MANUAL/RECOVERABLE/UNRECOVERABLE/UNKNOWN
  // Process:
  //   - Transition task to APPROVED
  //   - Record approval (operator ID, timestamp, notes)
  //   - Update orphan_classification status
  //   - Publish event: OrphanClassificationApproved
  //   - Log audit entry
  // Return: OrphanReviewApproval record

public void rejectClassification(
    UUID taskId,
    UUID operatorId,
    String reason
  )
  // Input: Task ID, operator, reason
  // Process:
  //   - Transition task to REJECTED
  //   - Re-classify (run algorithm again)
  //   - Create new review task with fresh evidence
  //   - Log rejection
  // Return: New OrphanReviewTask ID

public List<OrphanReviewTask> getPendingReviewTasks(UUID operatorId)
  // Input: Operator ID (null = all tasks)
  // Output: All PENDING_REVIEW tasks assigned to operator

public OrphanReviewTask getReviewTask(UUID taskId)
  // Input: Task ID
  // Output: Full task with classification and evidence
```

**Dependencies:**
- OrphanReviewTaskRepository
- OrphanClassificationRepository
- ApplicationEventPublisher (publish approval events)
- AuditLogService (log all decisions)

**Output:** OrphanReviewTask and OrphanReviewApproval entities

---

### Service 5: AuditLogService

**Purpose:** Log all orphan-related decisions for compliance and investigation

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public void logOrphanDetected(DetectedOrphanPosition orphan)
  // Log: Orphan position detected
  // Fields: timestamp, user_id, symbol, qty, broker_entry_time, source
  // Table: orphan_audit_log
  // Retention: 2 years

public void logClassificationRun(OrphanClassification classification)
  // Log: Classification algorithm executed
  // Fields: timestamp, orphan_id, classification, score, evidence_breakdown, blocking_issues
  // Table: orphan_audit_log
  // Used for: Verify algorithm correctness

public void logReviewTaskCreated(OrphanReviewTask task)
  // Log: Operator review task created
  // Fields: task_id, orphan_id, due_date, assigned_to
  // Table: orphan_audit_log

public void logReviewApproval(OrphanReviewApproval approval)
  // Log: Operator approved classification
  // Fields: task_id, operator_id, classification, notes, timestamp
  // Table: orphan_audit_log
  // Used for: Audit trail of operator decisions

public void logReviewRejection(UUID taskId, UUID operatorId, String reason)
  // Log: Operator rejected classification
  // Fields: task_id, operator_id, reason, timestamp
  // Table: orphan_audit_log

public List<AuditLogEntry> getOrphanAuditLog(UUID orphanId)
  // Input: Orphan position ID
  // Output: Complete history of all events
  // Retention: Full 2-year history

public List<AuditLogEntry> getOperatorDecisions(UUID operatorId, LocalDate dateRange)
  // Input: Operator ID, date range
  // Output: All decisions made by operator
  // Used for: Operator performance review
```

**Dependencies:**
- OrphanAuditLogRepository

**Output:** AuditLogEntry entities for compliance

---

## PART B: ENTITIES TO CREATE

### Entity 1: DetectedOrphanPosition

**Table:** `orphan_detected_positions`

**Fields:**
```
id                    UUID PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL
updated_at           TIMESTAMPTZ NOT NULL
version              BIGINT NOT NULL
deleted              BOOLEAN DEFAULT FALSE

user_id              UUID NOT NULL (FOREIGN KEY → users)
symbol               VARCHAR(64) NOT NULL
quantity             NUMERIC(24,8) NOT NULL
broker_entry_time    TIMESTAMPTZ NOT NULL (when broker shows entry)
broker_entry_price   NUMERIC(24,8)
current_value        NUMERIC(24,8)

detection_timestamp  TIMESTAMPTZ NOT NULL (when we detected it)
detection_source     VARCHAR(64) (SCHEDULER, MANUAL, API)

status               VARCHAR(32) (DETECTED, CLASSIFIED, REVIEWED, APPROVED, IGNORED)

notes                TEXT
```

**Indexes:**
```
idx_orphan_user_symbol (user_id, symbol)
idx_orphan_detected_time (detection_timestamp DESC)
idx_orphan_status (status)
```

**Lifecycle:**
```
DETECTED → CLASSIFIED → REVIEWED → APPROVED (Phase 2)
                    ↓
                 IGNORED (operator rejected as non-orphan)
```

---

### Entity 2: OrphanClassification

**Table:** `orphan_classification_results`

**Fields:**
```
id                   UUID PRIMARY KEY
created_at          TIMESTAMPTZ NOT NULL
updated_at          TIMESTAMPTZ NOT NULL
version             BIGINT NOT NULL

orphan_id           UUID NOT NULL (FK → orphan_detected_positions)
classification_type VARCHAR(32) NOT NULL (SYSTEM/MANUAL/RECOVERABLE/UNRECOVERABLE/UNKNOWN)
confidence_level    VARCHAR(32) NOT NULL (PROVEN/LIKELY/INFERRED/UNKNOWN)
evidence_score      INT NOT NULL (0-100)

supporting_evidence  TEXT (JSON array of evidence items)
blocking_issues     TEXT (JSON array of blocking conditions)

matched_signal_id   UUID (FK → strategy_signal, if applicable)
signal_confidence   INT (0-100, if signal found)

evidence_breakdown  TEXT (JSON with:
                    - timestamp_score: int
                    - quantity_score: int
                    - side_score: int
                    - price_score: int
                    - conflict_score: int
                    )

classification_reason TEXT (explanation of classification)

status              VARCHAR(32) (PENDING_REVIEW, APPROVED, REJECTED)
```

**Indexes:**
```
idx_classification_orphan (orphan_id)
idx_classification_type (classification_type)
idx_classification_score (evidence_score DESC)
idx_classification_status (status)
```

---

### Entity 3: OrphanReviewTask

**Table:** `orphan_review_tasks`

**Fields:**
```
id                    UUID PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL
updated_at           TIMESTAMPTZ NOT NULL
version              BIGINT NOT NULL

orphan_id            UUID NOT NULL (FK → orphan_detected_positions)
classification_id    UUID NOT NULL (FK → orphan_classification_results)

status               VARCHAR(32) NOT NULL (PENDING_REVIEW, APPROVED, REJECTED, EXPIRED)
assigned_operator_id UUID (FK → users, nullable = unassigned)
priority             VARCHAR(32) (LOW/MEDIUM/HIGH based on evidence score)

created_at           TIMESTAMPTZ NOT NULL
due_date             TIMESTAMPTZ NOT NULL (24 hours from creation)
completed_at         TIMESTAMPTZ (when approved/rejected)

# Full classification details denormalized for easy operator access:
symbol               VARCHAR(64)
broker_quantity      NUMERIC(24,8)
classification_type  VARCHAR(32)
evidence_score       INT
confidence_level     VARCHAR(32)

operator_notes       TEXT (operator instructions if needed)
```

**Indexes:**
```
idx_review_status (status)
idx_review_assigned (assigned_operator_id)
idx_review_due_date (due_date)
idx_review_priority (priority DESC)
```

---

### Entity 4: OrphanReviewApproval

**Table:** `orphan_review_approvals`

**Fields:**
```
id                    UUID PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL

orphan_id            UUID NOT NULL (FK → orphan_detected_positions)
review_task_id       UUID NOT NULL (FK → orphan_review_tasks)
operator_id          UUID NOT NULL (FK → users)

approved_classification VARCHAR(32) NOT NULL
operator_notes        TEXT
decision_timestamp    TIMESTAMPTZ NOT NULL

# For audit:
classification_before VARCHAR(32)
evidence_score_before INT
classification_after  VARCHAR(32)
evidence_score_after  INT

approval_reason       TEXT
```

**Indexes:**
```
idx_approval_operator (operator_id)
idx_approval_date (decision_timestamp DESC)
idx_approval_orphan (orphan_id)
```

---

### Entity 5: OrphanAuditLog

**Table:** `orphan_audit_log`

**Fields:**
```
id                    BIGSERIAL PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL

orphan_id            UUID NOT NULL
event_type           VARCHAR(64) NOT NULL (DETECTED, CLASSIFIED, REVIEW_CREATED, APPROVED, REJECTED, etc.)
event_source         VARCHAR(64) (SYSTEM, OPERATOR, SCHEDULER)

actor_id             UUID (who triggered this event)
actor_type           VARCHAR(32) (SYSTEM_SERVICE, OPERATOR, SCHEDULER)

event_details        TEXT (JSON with full event data)

# Search fields:
user_id              UUID NOT NULL
symbol               VARCHAR(64)
classification_type  VARCHAR(32)

retention_date       TIMESTAMPTZ (2 years from creation_at)
```

**Indexes:**
```
idx_audit_orphan (orphan_id)
idx_audit_type (event_type)
idx_audit_actor (actor_id)
idx_audit_date (created_at DESC)
idx_audit_symbol (symbol)
```

**Retention:** 2 years (configured in cleanup job)

---

## PART C: SCHEDULERS

### Scheduler 1: OrphanMonitorScheduler

**Frequency:** Every 15 minutes (configurable)

**Cron:** `0 */15 * * * *` (every 15 minutes)

**Time Window:** Off-peak hours option (e.g., 22:00-06:00 only)

**Method:**

```java
@Scheduled(cron = "${stokr.orphan.monitor-cron:0 */15 * * * *}")
public void scanAndClassifyOrphans()
  1. Log start: "orphan_monitor.started"
  2. Scan all active traders:
     - For each trader, call orphanDetectionService.scanForOrphans(userId)
     - Aggregate results
  3. For each detected orphan:
     - Create DetectedOrphanPosition entity
     - Call orphanClassificationService.classify(orphan)
     - Create OrphanClassification entity
     - If classification.confidence >= 85 or classification.status = RECOVERABLE:
       - Create OrphanReviewTask
       - Notify operators
  4. Log completion:
     - orphan_monitor.completed
     - Metrics:
       - total_orphans_detected
       - by_classification (SYSTEM/MANUAL/RECOVERABLE/etc)
       - by_confidence (PROVEN/LIKELY/etc)
  5. Alert if:
     - New orphans detected
     - Classification ambiguous (unknown/uncertain)
     - Recovery possible (RECOVERABLE)
```

**Error Handling:**
- Broker API failure → Log, skip scan, continue with next trader
- Database error → Log error, alert operator, pause scanner
- Classification failure → Log, flag for manual review

**Metrics to Emit:**
```
orphan_monitoring.scan_duration_ms
orphan_monitoring.orphans_detected
orphan_monitoring.classified_system (gauge)
orphan_monitoring.classified_manual (gauge)
orphan_monitoring.classified_recoverable (gauge)
orphan_monitoring.classified_unrecoverable (gauge)
orphan_monitoring.classified_unknown (gauge)
orphan_monitoring.evidence_score_distribution (histogram)
```

---

### Scheduler 2: StaleReviewTaskCleanupScheduler

**Frequency:** Once daily

**Cron:** `0 0 1 * * *` (01:00 daily)

**Purpose:** Expire orphan review tasks that are >24 hours old and not reviewed

**Method:**

```java
@Scheduled(cron = "${stokr.orphan.cleanup-cron:0 0 1 * * *}")
public void expireStaleReviewTasks()
  1. Query: OrphanReviewTask WHERE status = PENDING_REVIEW AND due_date < NOW()
  2. For each stale task:
     - Update status = EXPIRED
     - Log expiration
     - Notify operator (email: "Review task expired")
  3. Metrics:
     - expired_tasks_count
     - average_review_time (completed tasks)
```

---

### Scheduler 3: AuditLogArchivalScheduler

**Frequency:** Once weekly

**Cron:** `0 0 0 * * SUN` (Sunday midnight)

**Purpose:** Archive audit logs older than 2 years

**Method:**

```java
@Scheduled(cron = "${stokr.orphan.archive-cron:0 0 0 * * SUN}")
public void archiveOldAuditLogs()
  1. Query: OrphanAuditLog WHERE created_at < (NOW - 2 years)
  2. For each old log:
     - Export to archival table or file
     - Mark as archived
     - Delete from active table
  3. Metrics: archived_logs_count
```

---

## PART D: UI CHANGES

### Page 1: Orphan Positions Dashboard

**Route:** `/trading/positions/orphans`

**Permissions:** trader_admin role required

**Content:**

```
┌─────────────────────────────────────────────────────────────┐
│           ORPHANED POSITIONS MONITORING                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ [Refresh] [Export CSV] [Settings]                           │
│                                                              │
│ FILTERS:                                                     │
│ ├─ Status: [All] [Detected] [Classified] [Reviewed]        │
│ ├─ Classification: [All] [System] [Manual] [Recoverable]   │
│ ├─ Evidence Score: [All] [<50] [50-75] [75-85] [>85]       │
│ └─ Date Range: [Last 24h] [Last 7d] [All]                  │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│ SUMMARY CARDS                                               │
│ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐              │
│ │Total │ │Pending│ │System│ │Manual│ │Recover.│            │
│ │  5   │ │  2   │ │  1  │ │  1  │ │  1   │              │
│ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘              │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│ ORPHAN POSITIONS TABLE                                      │
│                                                              │
│ Symbol  │ Qty  │ Entry  │ Detected │ Class │ Score │ Status│
│━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━│
│NSE:SBIN │ 10  │09:50  │2h ago   │System │  92%  │ Reviewed
│         │      │₹710   │         │       │       │  ✓ APPROVED
│         │      │       │         │       │       │
│ [View Details] [View Task] [Audit Log]              │
│                                                      │
│NSE:ADANIPORTS│ 5 │ 10:15 │1h ago   │Manual │ 45% │ Pending
│         │      │₹2100  │         │       │       │  (Needs Review)
│         │      │       │         │       │       │
│ [View Details] [View Task] [Audit Log]              │
│                                                      │
│NSE:JSWSTEEL│ 20 │ 10:30 │30m ago  │Recover.│ 88% │ Pending
│         │      │₹715   │         │       │       │  (Can Recover)
│         │      │       │         │       │       │
│ [View Details] [Approve Recovery] [Reject] [Audit Log]│
│                                                      │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Real-time orphan list (updates every 15 min)
- Color-coded by classification (Green=System, Yellow=Manual, Blue=Recoverable, Red=Unrecoverable)
- Expandable detail rows
- Bulk actions: Select multiple → [Export Audit Log] [Create Tasks]

---

### Page 2: Orphan Details & Classification Evidence

**Route:** `/trading/positions/orphans/{orphanId}`

**Content:**

```
┌─────────────────────────────────────────────────────────────┐
│ NSE:JSWSTEEL (20 shares)                                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ POSITION DETAILS                                            │
│ ├─ Symbol: NSE:JSWSTEEL                                    │
│ ├─ Quantity: 20 shares                                      │
│ ├─ Entry Price: ₹715.50                                     │
│ ├─ Entry Time: 2026-06-10 10:57:28                         │
│ ├─ Current Value: ₹14,310                                   │
│ ├─ P&L: +₹310 (+2.2%)                                      │
│ └─ Detected: 30 minutes ago                                 │
│                                                              │
│ CLASSIFICATION ASSESSMENT                                   │
│ ├─ Classification: RECOVERABLE_ORPHAN                      │
│ ├─ Confidence: LIKELY (88%)                                │
│ ├─ Evidence Score: 88/100                                   │
│ │                                                           │
│ │ EVIDENCE BREAKDOWN                                        │
│ │ ├─ Signal found within 5min: ✅ YES (+25)               │
│ │ │   Signal ID: sig-abc123def                            │
│ │ │   Signal time: 10:57:20 (8 sec before broker order)  │
│ │ │                                                        │
│ │ ├─ Quantity matches: ✅ YES (+25)                       │
│ │ │   Signal qty: 20, Broker qty: 20 (exact match)      │
│ │ │                                                        │
│ │ ├─ Side matches: ✅ YES (+25)                           │
│ │ │   Signal side: BUY, Broker side: BUY                 │
│ │ │                                                        │
│ │ ├─ Entry price alignment: ✅ YES (+10)                  │
│ │ │   Signal ref: ₹714.50, Broker entry: ₹715.50 (0.1% gap)│
│ │ │                                                        │
│ │ └─ No conflicting signals: ✅ YES (+3)                  │
│ │     No other signals for JSWSTEEL at this time         │
│ │                                                          │
│ │ BLOCKING CONDITIONS                                      │
│ │ ├─ ❌ OMS order does NOT exist                          │
│ │     Why: Likely data loss during execution              │
│ │     Indicator: Signal found, broker filled, no OMS      │
│                                                            │
│ MATCHED SIGNAL (EVIDENCE)                                  │
│ ├─ Signal ID: sig-abc123def                               │
│ ├─ Strategy: INDEX_HUNT                                    │
│ ├─ Confidence: 73%                                         │
│ ├─ Created: 2026-06-10 10:57:20                           │
│ ├─ Reference Price: ₹714.50                               │
│ └─ [View Full Signal]                                      │
│                                                             │
│ RECOVERY RECOMMENDATION                                     │
│ ├─ Status: CAN RECOVER (Phase 2)                          │
│ ├─ Condition: Evidence score ≥ 85%                        │
│ ├─ Action: Recreate OMS order and execution records       │
│ ├─ Risk Level: LOW (high confidence evidence)             │
│ └─ [Request Recovery] (waiting for Phase 2)               │
│                                                             │
│ AUDIT TRAIL                                                 │
│ ├─ Detected: 2026-06-10 11:30 by SCHEDULER                │
│ ├─ Classified: 2026-06-10 11:30 as RECOVERABLE            │
│ ├─ Review Created: 2026-06-10 11:31                       │
│ └─ [Show Full Audit Log]                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Evidence breakdown with visual indicators (✅ yes, ❌ no, ⚠️ uncertain)
- Color-coded confidence (green ≥ 90%, yellow 70-89%, orange 50-69%, red < 50%)
- Direct link to matched signal
- Audit trail with timestamps
- [Request Recovery] button (disabled until Phase 2)

---

### Page 3: Operator Review Queue

**Route:** `/orphan-review`

**Permissions:** operator role required

**Content:**

```
┌─────────────────────────────────────────────────────────────┐
│              ORPHAN REVIEW QUEUE                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ [Assign to Me] [Reassign] [Bulk Approve] [Bulk Reject]    │
│                                                              │
│ FILTERS:                                                     │
│ ├─ Status: [All] [My Tasks] [Unassigned] [Urgent]         │
│ └─ Priority: [All] [High] [Medium] [Low]                   │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│ PENDING REVIEW TASKS                                        │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ 🔵 NSE:JSWSTEEL (20 qty)                       88%      ││
│ │                                                           ││
│ │ Classification: RECOVERABLE_ORPHAN                        ││
│ │ Evidence Score: 88/100 (LIKELY confidence)               ││
│ │ Signal Found: YES (sig-abc123def)                        ││
│ │ Entry Time: 10:57:28                                     ││
│ │ Detected: 30 minutes ago                                 ││
│ │                                                           ││
│ │ Quick Review:                                            ││
│ │ ✅ Signal within time window                            ││
│ │ ✅ Quantity matches (20 = 20)                           ││
│ │ ✅ Side matches (BUY = BUY)                             ││
│ │ ✅ Price alignment (< 1% gap)                           ││
│ │ ❌ OMS order missing (likely data loss)                 ││
│ │                                                           ││
│ │ [Approve as SYSTEM] [Approve as RECOVERABLE]            ││
│ │ [Reject] [Need More Info]                                ││
│ │ [View Full Details]                                      ││
│ └─────────────────────────────────────────────────────────┘│
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ 🟡 NSE:ADANIPORTS (5 qty)                     45%       ││
│ │                                                           ││
│ │ Classification: LIKELY_MANUAL                            ││
│ │ Evidence Score: 45/100 (INFERRED confidence)             ││
│ │ Signal Found: NO                                         ││
│ │ Entry Time: 10:15:30                                     ││
│ │ Detected: 1 hour ago                                     ││
│ │                                                           ││
│ │ Quick Review:                                            ││
│ │ ✅ No OMS order (not in system)                         ││
│ │ ✅ No matching signal                                    ││
│ │ ⚠️  Entry 2 hours after last system exit               ││
│ │ ⚠️  Could be manual, could be system recovery failure   ││
│ │                                                           ││
│ │ [Approve as MANUAL] [Approve as UNKNOWN]                ││
│ │ [Reject] [Need More Info]                                ││
│ │ [View Full Details]                                      ││
│ └─────────────────────────────────────────────────────────┘│
│                                                              │
│ Pagination: [First] [< Prev] [1] [2] [3] [Next >] [Last]  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Task assignment management
- Quick review summary (most important evidence)
- Approval buttons for each classification type
- Bulk actions (select multiple → approve/reject all)
- Time-to-review SLA indicators (24h due date)
- Link to full details

---

### Page 4: Audit Log Viewer

**Route:** `/orphan-review/audit/{orphanId}`

**Content:**

```
┌─────────────────────────────────────────────────────────────┐
│ AUDIT LOG: NSE:JSWSTEEL                                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ [Export Log] [Print]                                        │
│                                                              │
│ TIMELINE                                                     │
│                                                              │
│ 2026-06-10 11:31:45 UTC                                     │
│ └─ ⏹️  OPERATOR_REVIEWED (Admin: john.doe)                 │
│    ├─ Status: APPROVED                                      │
│    ├─ Classification: RECOVERABLE_ORPHAN                   │
│    ├─ Notes: "Clear data loss case, safe to recover"       │
│    └─ Previous: SYSTEM_MANAGED (classification changed)    │
│                                                              │
│ 2026-06-10 11:30:30 UTC                                     │
│ └─ 📋 REVIEW_TASK_CREATED                                  │
│    ├─ Task ID: task-xyz789                                  │
│    ├─ Assigned to: john.doe (john@example.com)             │
│    └─ Due: 2026-06-11 11:30:30                             │
│                                                              │
│ 2026-06-10 11:30:15 UTC                                     │
│ └─ 🔍 CLASSIFIED (System)                                   │
│    ├─ Classification: SYSTEM_MANAGED                       │
│    ├─ Score: 92/100                                         │
│    ├─ Confidence: LIKELY                                    │
│    ├─ Matched Signal: sig-abc123def (73% confidence)       │
│    ├─ Evidence:                                             │
│    │  ├─ Signal time: 10:57:20 (8 sec before broker order) │
│    │  ├─ Qty match: 20 = 20                                │
│    │  ├─ Side match: BUY = BUY                             │
│    │  ├─ Price gap: 0.1% (ref ₹714.50 vs entry ₹715.50)   │
│    │  └─ No conflicting signals                             │
│    └─ Blocking Issues:                                      │
│       └─ OMS order missing (likely data loss)              │
│                                                              │
│ 2026-06-10 11:30:00 UTC                                     │
│ └─ 🎯 DETECTED (Scheduler)                                  │
│    ├─ Source: ORPHAN_MONITOR_SCHEDULER                      │
│    ├─ Broker Position: NSE:JSWSTEEL, 20 qty                │
│    ├─ Entry Price: ₹715.50                                  │
│    ├─ Entry Time: 10:57:28                                  │
│    └─ Detection: No matching OMS order (execution_mode=LIVE)│
│                                                              │
│ SEARCH: [By Date] [By Operator] [By Event Type]            │
│                                                              │
│ EXPORT: [CSV] [JSON] [PDF]                                  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Complete timeline of all events
- Color-coded event types
- Operator decisions tracked
- Evidence scoring breakdown
- Exportable audit log

---

## PART E: EVENT FLOWS

### Event Flow 1: Orphan Detection & Classification

```
Timeline: Every 15 minutes (OrphanMonitorScheduler)

┌─────────────────────────────────────────────────────┐
│ SCHEDULER STARTS SCAN                               │
└────────────────┬────────────────────────────────────┘
                 │
                 ↓
        ┌──────────────────────┐
        │ For each active user:│
        │ 1. Fetch broker pos  │
        │ 2. Fetch OMS orders  │
        │ 3. Compare → orphans │
        └────────┬─────────────┘
                 │
        ┌────────┴───────────────────────────┐
        │                                    │
        ↓ (Orphan found)                    ↓ (No orphan)
┌──────────────────────┐            Continue to next user
│ Create Detection     │
│ - DetectedOrphanPos. │
│ - Log audit event    │
└────────┬─────────────┘
         │
         ↓
┌──────────────────────────────┐
│ Run Classification Algorithm │
│ - Search for matching signal │
│ - Calculate evidence score   │
│ - Determine classification   │
└────────┬────────────────────┘
         │
         ↓
┌──────────────────────────────┐
│ Create Classification Result │
│ - OrphanClassification entity│
│ - Log audit event            │
│ - Publish event              │
└────────┬────────────────────┘
         │
   ┌─────┴──────────────┐
   │                    │
   ↓                    ↓
(Score ≥ 85%)     (Score < 85%)
(or RECOVERABLE)  (or UNKNOWN)
   │                    │
   ↓                    ↓
CREATE TASK       NO TASK
Notify operator   Log as uncertain

┌──────────────────────────────────┐
│ Event: OrphanClassificationFound │
│ Publish to event bus             │
│ Trigger notifications            │
└──────────────────────────────────┘
```

---

### Event Flow 2: Operator Review & Approval

```
Timeline: Operator opens orphan review task

┌──────────────────────────────────────┐
│ OPERATOR VIEWS REVIEW TASK           │
│ (From /orphan-review dashboard)      │
└────────────┬───────────────────────┘
             │
             ↓
┌───────────────────────────────────┐
│ UI displays:                       │
│ - Orphan details                  │
│ - Classification evidence         │
│ - Matched signal (if any)         │
│ - Approval options                │
└────────────┬─────────────────────┘
             │
             ↓
┌───────────────────────────────────┐
│ OPERATOR CLICKS [APPROVE]         │
│ Selects classification option     │
│ (SYSTEM/MANUAL/RECOVERABLE/etc.)  │
│ Enters notes                       │
└────────────┬─────────────────────┘
             │
             ↓
┌───────────────────────────────────┐
│ APPROVAL SUBMITTED                │
│ 1. Create OrphanReviewApproval    │
│ 2. Update OrphanReviewTask status │
│ 3. Update DetectedOrphanPosition  │
│ 4. Log audit event                │
│ 5. Publish event                  │
└────────────┬─────────────────────┘
             │
             ↓
┌───────────────────────────────────┐
│ Event: OrphanApprovalSubmitted    │
│ Payload:                          │
│ - orphan_id                       │
│ - classification_type             │
│ - operator_id                     │
│ - notes                           │
│ - timestamp                       │
└────────────┬─────────────────────┘
             │
             ↓
┌────────────────────────────────────────┐
│ IF classification = RECOVERABLE:       │
│   → Create recovery task (Phase 2)    │
│ ELSE:                                  │
│   → Mark as REVIEWED                   │
│   → Notify system status               │
└────────────────────────────────────────┘
```

---

## PART F: AUDIT LOGGING

### Audit Log Entries

Every operation logs to `orphan_audit_log` table:

**Event: ORPHAN_DETECTED**
```
{
  "event_type": "ORPHAN_DETECTED",
  "event_source": "SCHEDULER",
  "orphan_id": "orphan-abc123",
  "user_id": "user-xyz789",
  "symbol": "NSE:JSWSTEEL",
  "broker_quantity": 20,
  "broker_entry_price": 715.50,
  "broker_entry_time": "2026-06-10T10:57:28Z",
  "detection_time": "2026-06-10T11:30:00Z",
  "actor_id": "system-scheduler",
  "actor_type": "SYSTEM_SERVICE"
}
```

**Event: CLASSIFICATION_RUN**
```
{
  "event_type": "CLASSIFICATION_RUN",
  "event_source": "SYSTEM",
  "orphan_id": "orphan-abc123",
  "classification_type": "SYSTEM_MANAGED",
  "confidence_level": "LIKELY",
  "evidence_score": 92,
  "evidence_breakdown": {
    "signal_alignment": 25,
    "quantity_match": 25,
    "side_match": 25,
    "price_alignment": 10,
    "conflict_check": 7
  },
  "matched_signal_id": "sig-abc123def",
  "blocking_issues": ["OMS order missing"],
  "actor_id": "system-classification-service",
  "actor_type": "SYSTEM_SERVICE"
}
```

**Event: REVIEW_TASK_CREATED**
```
{
  "event_type": "REVIEW_TASK_CREATED",
  "event_source": "SYSTEM",
  "task_id": "task-xyz789",
  "orphan_id": "orphan-abc123",
  "assigned_operator_id": "operator-001",
  "priority": "HIGH",
  "due_date": "2026-06-11T11:30:00Z",
  "actor_id": "system-workflow",
  "actor_type": "SYSTEM_SERVICE"
}
```

**Event: OPERATOR_APPROVAL**
```
{
  "event_type": "OPERATOR_APPROVAL",
  "event_source": "OPERATOR",
  "task_id": "task-xyz789",
  "orphan_id": "orphan-abc123",
  "operator_id": "operator-001",
  "approved_classification": "RECOVERABLE_ORPHAN",
  "classification_before": "SYSTEM_MANAGED",
  "evidence_score_before": 92,
  "evidence_score_after": 92,
  "operator_notes": "Clear data loss case, safe to recover",
  "decision_time": "2026-06-10T11:31:45Z",
  "actor_id": "operator-001",
  "actor_type": "OPERATOR",
  "actor_email": "john.doe@example.com"
}
```

**Event: OPERATOR_REJECTION**
```
{
  "event_type": "OPERATOR_REJECTION",
  "event_source": "OPERATOR",
  "task_id": "task-xyz789",
  "orphan_id": "orphan-abc123",
  "operator_id": "operator-001",
  "rejection_reason": "Position matches my manual trade from this morning",
  "reclassification_triggered": true,
  "new_task_created": "task-new123",
  "actor_id": "operator-001",
  "actor_type": "OPERATOR"
}
```

---

### Audit Log Queries

**For compliance/investigation:**

```sql
-- Get all events for an orphan
SELECT * FROM orphan_audit_log 
WHERE orphan_id = 'orphan-abc123'
ORDER BY created_at ASC;

-- Get all operator approvals
SELECT * FROM orphan_audit_log
WHERE event_type = 'OPERATOR_APPROVAL'
AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
ORDER BY created_at DESC;

-- Get average review time
SELECT 
  AVG(EXTRACT(EPOCH FROM (completed_at - created_at)))::INT as avg_review_seconds
FROM orphan_review_approvals
WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY);

-- Audit specific operator
SELECT * FROM orphan_audit_log
WHERE actor_id = 'operator-001'
AND event_type IN ('OPERATOR_APPROVAL', 'OPERATOR_REJECTION')
ORDER BY created_at DESC;
```

---

## PART G: DATABASE MIGRATIONS

### Migration Files Required

**V105__create_orphan_detection_tables.sql**
```
- Create orphan_detected_positions table
- Create orphan_classification_results table
- Create indexes
- Add constraints
```

**V106__create_orphan_review_workflow.sql**
```
- Create orphan_review_tasks table
- Create orphan_review_approvals table
- Create foreign keys
- Add indexes
```

**V107__create_orphan_audit_log.sql**
```
- Create orphan_audit_log table
- Create retention policy
- Add indexes for common queries
- Add partitioning by date (monthly)
```

---

## PART H: DEPENDENCIES & CONFIGURATION

### New Properties (application.yml)

```yaml
stokr:
  orphan:
    monitor:
      enabled: true
      cron: "0 */15 * * * *"  # Every 15 minutes
      timezone: "Asia/Kolkata"
    
    classification:
      time-window-minutes: 5
      evidence-score-threshold: 85
      confidence-minimum: "LIKELY"
    
    review:
      task-due-hours: 24
      enabled: true
    
    cleanup:
      enabled: true
      cron: "0 0 1 * * *"  # Daily at 1 AM
      archive-cron: "0 0 0 * * SUN"  # Weekly
    
    audit:
      retention-days: 730  # 2 years
      partitioning: "monthly"
    
    notifications:
      enabled: true
      new-orphan-alert: true
      recoverable-alert: true
      review-task-alert: true
```

### Service Dependencies

```
OrphanPositionDetectionService
  ↓ depends on
  ├─ BrokerPositionTruthService
  ├─ OmsOrderRepository
  ├─ OrphanPositionRepository
  └─ AuditLogService

OrphanClassificationService
  ↓ depends on
  ├─ StrategySignalRepository
  ├─ OmsExecutionRepository
  ├─ EvidenceScoringService
  └─ OrphanClassificationRepository

OperatorReviewWorkflowService
  ↓ depends on
  ├─ OrphanReviewTaskRepository
  ├─ OrphanClassificationRepository
  ├─ ApplicationEventPublisher
  └─ AuditLogService

AuditLogService
  ↓ depends on
  └─ OrphanAuditLogRepository
```

---

## PART I: IMPLEMENTATION CHECKLIST

### Code Changes Required

- [ ] Create OrphanPositionDetectionService
- [ ] Create OrphanClassificationService
- [ ] Create EvidenceScoringService
- [ ] Create OperatorReviewWorkflowService
- [ ] Create AuditLogService
- [ ] Create OrphanMonitorScheduler
- [ ] Create StaleReviewTaskCleanupScheduler
- [ ] Create AuditLogArchivalScheduler

### Database Changes Required

- [ ] V105 migration (orphan detection tables)
- [ ] V106 migration (review workflow tables)
- [ ] V107 migration (audit log table)
- [ ] Repositories for all entities
- [ ] JPA entities for all tables

### UI Changes Required

- [ ] Orphan Positions Dashboard page
- [ ] Orphan Details & Evidence page
- [ ] Operator Review Queue page
- [ ] Audit Log Viewer page
- [ ] Navigation menu updates
- [ ] Permission enforcement (operator role)

### Event/Messaging Required

- [ ] OrphanClassificationFound event
- [ ] OrphanApprovalSubmitted event
- [ ] Event publishers and listeners
- [ ] Notification service integration

### Testing Required

- [ ] Unit tests for detection algorithm
- [ ] Unit tests for classification algorithm
- [ ] Unit tests for scoring algorithm
- [ ] Integration tests with real database
- [ ] UI tests for all pages
- [ ] Audit log tests
- [ ] Scheduler tests

### Configuration Required

- [ ] application.yml properties
- [ ] Feature flags (enable/disable monitoring)
- [ ] Cron schedules
- [ ] Time zones
- [ ] Retention policies

### Documentation Required

- [ ] API documentation
- [ ] Operator manual
- [ ] Administrator guide
- [ ] Troubleshooting guide

---

## PART J: DEPLOYMENT PLAN

### Phase 1 Deployment Steps

1. **Week 1: Core Services**
   - Deploy database migrations
   - Deploy all services
   - Deploy schedulers (initially disabled)
   - Deploy audit logging
   - Test in staging environment

2. **Week 2: UI Rollout**
   - Deploy UI pages
   - Deploy navigation updates
   - Deploy role-based access control
   - User acceptance testing

3. **Week 3: Soft Launch**
   - Enable monitoring scheduler in production (read-only)
   - Monitor for false positives
   - Collect baseline data
   - Operator training

4. **Week 4: Full Launch**
   - Enable operator review workflow
   - Enable alerts and notifications
   - Full production monitoring
   - Documentation complete

### Rollback Plan

If issues arise:
- Disable OrphanMonitorScheduler
- All data already persisted and queryable
- UI stays available for historical review
- No data loss, no production impact

---

## APPROVAL GATE

**AWAITING APPROVAL BEFORE CODING BEGINS**

This plan includes:
- ✅ 5 new services
- ✅ 5 new entities
- ✅ 3 new schedulers
- ✅ 4 new UI pages
- ✅ Complete event flows
- ✅ Audit logging strategy
- ✅ Database migrations
- ✅ Configuration properties
- ✅ Implementation checklist

**Ready to code Phase 1 upon approval.**

**PHASE 2 (Recovery) will be separate after Phase 1 is validated in production.**

