# PHASE 1 REVISED IMPLEMENTATION PLAN
## Orphan Position Monitoring & Classification (No Recovery, No Automatic Actions)

**Date:** 2026-06-10  
**Scope:** Monitoring, classification, evidence collection, operator review only  
**NO Recovery Actions** (Phase 2)  
**NO Automatic Position Management** (all phases)  
**Status:** Design-only, awaiting approval before coding  

---

## EXECUTIVE SUMMARY

Phase 1 builds the foundation for understanding orphaned positions:
- ✅ Continuous orphan detection (every 1-2 minutes)
- ✅ Continuous broker position snapshots
- ✅ Classify into evidence-based categories
- ✅ Score evidence strength
- ✅ Surface to operators for review
- ✅ Complete audit trail
- ❌ NO automatic actions
- ❌ NO position modifications
- ❌ NO OMS order creation
- ❌ NO execution creation
- ❌ NO recovery actions
- ❌ NO exit commands
- ❌ NO position management

Every orphan stays in REVIEW_REQUIRED state until:
1. Operator explicitly approves classification
2. Production evidence supports the classification
3. Phase 2 (future) is implemented with full safety gates

**CRITICAL CONSTRAINT:** System never claims "MANUAL" or "LIKELY_MANUAL" unless broker metadata explicitly proves manual entry. Default classifications are UNKNOWN_ORIGIN or UNVERIFIED_EXTERNAL — evidence must affirmatively prove origin before changing classification.

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
  // Frequency: Called by OrphanMonitorScheduler every 1-2 minutes

public List<DetectedOrphanPosition> scanAllOrphans()
  // Input: None
  // Process: Scan all active traders
  // Output: All orphaned positions across platform
  // Frequency: Full scan once per hour (off-peak)

public void recordOrphanDetection(DetectedOrphanPosition orphan)
  // Persist detection to orphan_detected_positions table
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
- BrokerPositionObservationRepository (record snapshots)
- OmsOrderRepository (fetch OMS orders)
- OrphanPositionRepository (persist detections)

**Error Handling:**
- Broker API failures → Log, skip scan, try again in 1-2min
- Database errors → Log, alert operator, fail fast
- Empty results → Normal, log as "no orphans detected"

---

### Service 2: BrokerPositionSnapshotService

**Purpose:** Record continuous broker position snapshots for investigation trail

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public void recordSnapshot(UUID userId)
  // Input: User ID
  // Process:
  //   1. Fetch current broker positions
  //   2. Create BrokerPositionObservation for each position
  //   3. Persist with timestamp
  // Frequency: Called every 1-2 minutes alongside orphan scan

public List<BrokerPositionObservation> getPositionHistory(UUID userId, String symbol, LocalDateTime from, LocalDateTime to)
  // Input: User, symbol, date range
  // Output: All broker position snapshots for symbol in range
  // Use for: Tracing when position appeared, disappeared, quantity changed

public List<BrokerPositionObservation> getLatestSnapshot(UUID userId)
  // Input: User ID
  // Output: Most recent position snapshot for all user's symbols
  // Use for: Current state view
```

**Dependencies:**
- BrokerPositionTruthService (fetch broker positions)
- BrokerPositionObservationRepository (persist snapshots)

**Error Handling:**
- Broker API failures → Log, skip snapshot, continue
- Database errors → Log, alert operator

---

### Service 3: OrphanClassificationService

**Purpose:** Classify each orphan into evidence-based categories (NEVER assumes MANUAL without broker proof)

**Package:** `com.stokr.execution.orphan`

**Key Methods:**

```java
public OrphanClassification classify(DetectedOrphanPosition orphan)
  // Input: Orphan position
  // Process: Run classification algorithm (see Part B)
  // Output: OrphanClassification with:
  //   - classification type (SYSTEM_MANAGED/UNKNOWN_ORIGIN/UNVERIFIED_EXTERNAL/UNRECOVERABLE/REVIEW_REQUIRED)
  //   - evidence score (0-100)
  //   - confidence level (PROVEN/LIKELY/INFERRED/UNKNOWN)
  //   - supporting evidence list
  //   - blocking issues list
  //   - DO_NOT_TOUCH indicator if uncertain
  // Frequency: Called immediately after orphan detection

private ClassificationResult classifyAsSystemManaged(DetectedOrphanPosition orphan)
  // Rule: OMS order exists + signal exists + execution exists
  // Evidence required:
  //   - oms_order.signal_id matches found signal
  //   - oms_execution found with matching quantity
  //   - All timestamps align within 1 second
  // Return: ClassificationResult with PROVEN confidence only

private ClassificationResult classifyAsUnknownOrigin(DetectedOrphanPosition orphan)
  // Rule: No OMS order, no signal, insufficient evidence to classify otherwise
  // Evidence: Position exists at broker, no system records found
  // Return: ClassificationResult with UNKNOWN confidence
  // Note: This is the DEFAULT when evidence is missing or ambiguous

private ClassificationResult classifyAsUnverifiedExternal(DetectedOrphanPosition orphan)
  // Rule: Evidence suggests external origin but cannot be proven
  // ONLY set if there is AFFIRMATIVE evidence of external entry:
  //   - Broker timestamp indicates manual entry (requires broker metadata)
  //   - OR position entry logged by non-system user in audit trail
  //   - OR explicit broker metadata field says "placed_by: manual"
  // Otherwise → UNKNOWN_ORIGIN
  // Return: ClassificationResult with INFERRED confidence

private ClassificationResult classifyAsUnrecoverableOrphan(DetectedOrphanPosition orphan)
  // Rule: Position is too old (>30 days) or highly ambiguous
  // Cannot safely recover due to:
  //   - Stale position with no recent activity
  //   - Conflicting signals from multiple timestamps
  //   - Historical data loss
  // Return: ClassificationResult with UNKNOWN confidence

private ClassificationResult markDoNotTouch(DetectedOrphanPosition orphan)
  // Rule: Mark position as DO_NOT_TOUCH if:
  //   - Evidence is contradictory
  //   - Multiple possible origins
  //   - Timestamp gaps >2 hours
  //   - System cannot safely make any determination
  // Return: ClassificationResult with DO_NOT_TOUCH status
```

**CRITICAL RULES:**
- **NEVER output MANUAL or LIKELY_MANUAL without broker metadata proving entry type**
- **DEFAULT is UNKNOWN_ORIGIN when evidence missing**
- **UNVERIFIED_EXTERNAL only if affirmative external proof exists**
- **DO_NOT_TOUCH when certain positions are too dangerous to classify**
- **All classifications are subject to operator review**

**Dependencies:**
- StrategySignalRepository (find matching signal)
- OmsExecutionRepository (find matching execution)
- BrokerPositionObservationRepository (get position history)
- EvidenceScoringService (calculate confidence score)

**Output:** OrphanClassification entity persisted to orphan_classification_results

---

### Service 4: EvidenceScoringService

**Purpose:** Calculate confidence score for each classification (0-100 scale)

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
  //   - Signal not found: 0 (not a blocker, just missing evidence)

private int scoreQuantityMatch(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-25 points
  // Criteria:
  //   - Exact match: +25
  //   - Within 1%: +20
  //   - Within 5%: +10
  //   - Mismatch >5%: 0 (reduces confidence)

private int scoreSideMatch(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-25 points
  // Criteria:
  //   - BUY=BUY or SELL=SELL: +25
  //   - Mismatch: 0 (reduces confidence but not blocker)

private int scorePriceAlignment(DetectedOrphanPosition orphan, StrategySignalEntity signal)
  // Score: 0-15 points
  // Criteria:
  //   - Entry price within 1% of signal reference: +15
  //   - Within 2%: +10
  //   - Within 5%: +5
  //   - >5% mismatch: 0

private int scoreConflictingSignals(DetectedOrphanPosition orphan)
  // Score: 0-10 points
  // Criteria:
  //   - No conflicting signals: +10
  //   - One conflicting signal: 0 (raises uncertainty)
  //   - Multiple conflicting signals: 0 (high uncertainty)

private int scoreExternalMetadata(DetectedOrphanPosition orphan)
  // Score: 0-10 points
  // ONLY award points if broker metadata affirmatively proves external entry:
  //   - Broker API field "placed_by" = "manual": +10
  //   - Broker order tags indicate manual origin: +10
  //   - Otherwise: 0 (cannot prove external entry)
```

**BLOCKING CONDITIONS (reduce confidence, DON'T block scoring):**
- Timestamp gaps > 30 minutes (raises DO_NOT_TOUCH risk)
- Position > 30 days old (raises UNRECOVERABLE risk)
- Multiple conflicting signals (raises DO_NOT_TOUCH risk)

**Dependencies:**
- StrategySignalRepository
- OmsOrderRepository
- OmsExecutionRepository
- BrokerPositionObservationRepository

**Output:** EvidenceScore entity for audit trail

---

### Service 5: OperatorReviewWorkflowService

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
  //   - Classification is one of: SYSTEM_MANAGED/UNKNOWN_ORIGIN/UNVERIFIED_EXTERNAL/UNRECOVERABLE/REVIEW_REQUIRED/DO_NOT_TOUCH
  // Process:
  //   - Transition task to APPROVED
  //   - Record approval (operator ID, timestamp, notes)
  //   - Update orphan_classification status
  //   - Publish event: OrphanClassificationApproved
  //   - Log audit entry
  // Return: OrphanReviewApproval record
  // NOTE: Operator can override system classification if they have evidence

public void rejectClassification(
    UUID taskId,
    UUID operatorId,
    String reason
  )
  // Input: Task ID, operator, reason
  // Process:
  //   - Transition task to REJECTED
  //   - Re-classify (run algorithm again with operator feedback)
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

### Service 6: AuditLogService

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

### Entity 1: BrokerPositionObservation

**Table:** `broker_position_observations`

**Purpose:** Continuous snapshots of broker positions for investigation trail

**Fields:**
```
id                    UUID PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL
version              BIGINT NOT NULL

user_id              UUID NOT NULL (FOREIGN KEY → users)
symbol               VARCHAR(64) NOT NULL
broker_quantity      NUMERIC(24,8) NOT NULL
broker_entry_price   NUMERIC(24,8)
broker_entry_time    TIMESTAMPTZ
observation_time     TIMESTAMPTZ NOT NULL (when we took snapshot)

# Broker-provided metadata (if available)
broker_order_id      VARCHAR(64)
broker_position_type VARCHAR(32) (INTRADAY, MIS, CNC, etc.)
broker_entry_source  VARCHAR(128) (manual, API, unknown)
broker_tags          TEXT (JSON array of order tags from broker)

# For tracing position lifecycle
is_orphaned          BOOLEAN (true if no matching OMS order)
matched_oms_order_id UUID (if OMS order found)

notes                TEXT
```

**Indexes:**
```
idx_observation_user_symbol_time (user_id, symbol, observation_time DESC)
idx_observation_is_orphaned (is_orphaned, observation_time DESC)
idx_observation_time (observation_time DESC)
```

**Lifecycle:**
- Created every 1-2 minutes for each active position
- Used for historical analysis: when did position appear? when disappear? qty changes?

---

### Entity 2: DetectedOrphanPosition

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

status               VARCHAR(32) (DETECTED, CLASSIFIED, REVIEWED, APPROVED, IGNORED, DO_NOT_TOUCH)

notes                TEXT
```

**Indexes:**
```
idx_orphan_user_symbol (user_id, symbol)
idx_orphan_detected_time (detection_timestamp DESC)
idx_orphan_status (status)
idx_orphan_do_not_touch (status = 'DO_NOT_TOUCH')
```

**Lifecycle:**
```
DETECTED → CLASSIFIED → REVIEWED → APPROVED (Phase 2 planning)
                   ↓
                DO_NOT_TOUCH (uncertain positions)
                   ↓
                IGNORED (operator rejected as non-orphan)
```

---

### Entity 3: OrphanClassification

**Table:** `orphan_classification_results`

**Fields:**
```
id                   UUID PRIMARY KEY
created_at          TIMESTAMPTZ NOT NULL
updated_at          TIMESTAMPTZ NOT NULL
version             BIGINT NOT NULL

orphan_id           UUID NOT NULL (FK → orphan_detected_positions)
classification_type VARCHAR(32) NOT NULL (SYSTEM_MANAGED/UNKNOWN_ORIGIN/UNVERIFIED_EXTERNAL/UNRECOVERABLE/REVIEW_REQUIRED/DO_NOT_TOUCH)
confidence_level    VARCHAR(32) NOT NULL (PROVEN/LIKELY/INFERRED/UNKNOWN)
evidence_score      INT NOT NULL (0-100)

supporting_evidence  TEXT (JSON array of evidence items)
blocking_issues     TEXT (JSON array of blocking conditions)

matched_signal_id   UUID (FK → strategy_signal, if applicable)
signal_confidence   INT (0-100, if signal found)

# Broker metadata findings (if any)
broker_entry_source_found BOOLEAN (did we find broker metadata about entry?)
broker_entry_source_value VARCHAR(128) (what did broker metadata say?)

evidence_breakdown  TEXT (JSON with:
                    - timestamp_score: int
                    - quantity_score: int
                    - side_score: int
                    - price_score: int
                    - conflict_score: int
                    - external_metadata_score: int
                    )

classification_reason TEXT (explanation of classification)

status              VARCHAR(32) (PENDING_REVIEW, APPROVED, REJECTED, DO_NOT_TOUCH)
```

**Indexes:**
```
idx_classification_orphan (orphan_id)
idx_classification_type (classification_type)
idx_classification_score (evidence_score DESC)
idx_classification_status (status)
idx_classification_do_not_touch (status = 'DO_NOT_TOUCH')
```

---

### Entity 4: OrphanReviewTask

**Table:** `orphan_review_tasks`

**Fields:**
```
id                    UUID PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL
updated_at           TIMESTAMPTZ NOT NULL
version              BIGINT NOT NULL

orphan_id            UUID NOT NULL (FK → orphan_detected_positions)
classification_id    UUID NOT NULL (FK → orphan_classification_results)

status               VARCHAR(32) NOT NULL (PENDING_REVIEW, APPROVED, REJECTED, EXPIRED, DO_NOT_TOUCH)
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
do_not_touch_reason  TEXT (if status = DO_NOT_TOUCH, why we flagged it)
```

**Indexes:**
```
idx_review_status (status)
idx_review_assigned (assigned_operator_id)
idx_review_due_date (due_date)
idx_review_priority (priority DESC)
idx_review_do_not_touch (status = 'DO_NOT_TOUCH')
```

---

### Entity 5: OrphanReviewApproval

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

### Entity 6: OrphanAuditLog

**Table:** `orphan_audit_log`

**Fields:**
```
id                    BIGSERIAL PRIMARY KEY
created_at           TIMESTAMPTZ NOT NULL

orphan_id            UUID NOT NULL
event_type           VARCHAR(64) NOT NULL (DETECTED, CLASSIFIED, REVIEW_CREATED, APPROVED, REJECTED, DO_NOT_TOUCH_SET, etc.)
event_source         VARCHAR(64) (SYSTEM, OPERATOR, SCHEDULER)

actor_id             UUID (who triggered this event)
actor_type           VARCHAR(32) (SYSTEM_SERVICE, OPERATOR, SCHEDULER)

event_details        TEXT (JSON with full event data)

# Search fields:
user_id              UUID NOT NULL
symbol               VARCHAR(64)
classification_type  VARCHAR(32)

retention_date       TIMESTAMPTZ (2 years from created_at)
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

**Frequency:** Every 1-2 minutes (configurable, default 1 minute)

**Cron:** `0 * * * * *` (every minute) or `*/2 * * * * *` (every 2 minutes)

**Time Window:** Off-peak hours option (e.g., 22:00-06:00 only)

**Method:**

```java
@Scheduled(cron = "${stokr.orphan.monitor-cron:0 * * * * *}")
public void scanAndClassifyOrphans()
  1. Log start: "orphan_monitor.started"
  2. Call BrokerPositionSnapshotService.recordSnapshot() for all users
  3. Scan all active traders:
     - For each trader, call orphanDetectionService.scanForOrphans(userId)
     - Aggregate results
  4. For each detected orphan:
     - Create DetectedOrphanPosition entity
     - Call orphanClassificationService.classify(orphan)
     - Create OrphanClassification entity
     - If classification.status = DO_NOT_TOUCH:
       - Log with elevated priority
       - Create review task with "DO_NOT_TOUCH" flag
       - Notify operators: "Position requires careful review"
     - Else if classification.confidence >= LIKELY:
       - Create OrphanReviewTask
       - Notify operators
  5. Log completion:
     - orphan_monitor.completed
     - Metrics:
       - total_orphans_detected
       - by_classification (SYSTEM_MANAGED/UNKNOWN_ORIGIN/UNVERIFIED_EXTERNAL/etc)
       - by_confidence (PROVEN/LIKELY/INFERRED/UNKNOWN)
       - do_not_touch_count
  6. Alert if:
     - New orphans detected with DO_NOT_TOUCH flag
     - Classification highly uncertain
```

**Error Handling:**
- Broker API failure → Log, skip scan, continue with next trader
- Database error → Log error, alert operator, pause scanner
- Classification failure → Log, flag for manual review

**Metrics to Emit:**
```
orphan_monitoring.scan_duration_ms
orphan_monitoring.orphans_detected
orphan_monitoring.classified_system_managed (gauge)
orphan_monitoring.classified_unknown_origin (gauge)
orphan_monitoring.classified_unverified_external (gauge)
orphan_monitoring.classified_unrecoverable (gauge)
orphan_monitoring.classified_do_not_touch (gauge)
orphan_monitoring.evidence_score_distribution (histogram)
orphan_monitoring.broker_position_snapshots_recorded (counter)
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
  1. Query: OrphanReviewTask WHERE status IN (PENDING_REVIEW, DO_NOT_TOUCH) AND due_date < NOW()
  2. For each stale task:
     - Update status = EXPIRED
     - Log expiration
     - Notify operator (email: "Review task expired without action")
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
│ ├─ Classification: [All] [System] [Unknown] [Unverified]   │
│ ├─ Evidence Score: [All] [<50] [50-75] [75-85] [>85]       │
│ ├─ Priority: [All] [DO_NOT_TOUCH] [High] [Medium] [Low]    │
│ └─ Date Range: [Last 24h] [Last 7d] [All]                  │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│ SUMMARY CARDS                                               │
│ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐      │
│ │Total │ │Pending│ │System│ │Unknown│ │Unreachable││DO_NOT_TOUCH│
│ │  5   │ │  2   │ │  1  │ │  1   │  │  0   │ │ 1 🔴  │
│ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘      │
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
│NSE:ADANIPORTS│ 5 │ 10:15 │1h ago   │Unknown │ 45% │ Pending
│         │      │₹2100  │         │        │       │  (Needs Review)
│         │      │       │         │        │       │
│ [View Details] [View Task] [Audit Log]              │
│                                                      │
│NSE:JSWSTEEL│ 20 │ 10:30 │30m ago  │Unverified│ 88% │ DO_NOT_TOUCH 🔴
│         │      │₹715   │         │         │       │  (Careful Review)
│         │      │       │         │         │       │
│ [View Details] [View Evidence] [View Task] [Audit Log]│
│                                                      │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Real-time orphan list (updates every 1-2 min)
- Color-coded by classification (Green=System, Yellow=Unknown, Orange=Unverified, Red=DO_NOT_TOUCH)
- Expandable detail rows
- Bulk actions: Select multiple → [Export Audit Log]
- **NO recovery buttons, NO exit buttons, NO modification buttons**

---

### Page 2: Orphan Details & Classification Evidence

**Route:** `/trading/positions/orphans/{orphanId}`

**Content:**

```
┌─────────────────────────────────────────────────────────────┐
│ NSE:JSWSTEEL (20 shares) 🔴 [DO_NOT_TOUCH]                 │
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
│ ├─ Classification: UNVERIFIED_EXTERNAL                      │
│ ├─ Status: DO_NOT_TOUCH (contradictory evidence)           │
│ ├─ Confidence: INFERRED (70%)                              │
│ ├─ Evidence Score: 70/100                                   │
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
│ │ ├─ Entry price alignment: ⚠️ PARTIAL (+5)              │
│ │ │   Signal ref: ₹714.50, Broker entry: ₹715.50 (0.1% gap)│
│ │ │                                                        │
│ │ └─ External metadata check: ⚠️ CONFLICTING (0)          │
│ │     Broker metadata unclear about entry source           │
│ │                                                          │
│ │ BLOCKING CONDITIONS                                      │
│ │ ├─ ❌ OMS order does NOT exist                          │
│ │ │   BUT signal exists with matching properties          │
│ │ │   AND broker metadata is inconclusive                 │
│ │ │   → Cannot safely determine if system or manual       │
│ │                                                          │
│ │ ├─ ⚠️  Timestamp gap: 30 minutes                        │
│ │     Large gap between entry and next activity           │
│ │                                                          │
│ │ ├─ ⚠️  Contradictory evidence                          │
│ │     Signal says SYSTEM, Broker metadata unclear         │
│ │     → Flagged as DO_NOT_TOUCH until clarified          │
│ │                                                          │
│ MATCHED SIGNAL (EVIDENCE)                                  │
│ ├─ Signal ID: sig-abc123def                               │
│ ├─ Strategy: INDEX_HUNT                                    │
│ ├─ Confidence: 73%                                         │
│ ├─ Created: 2026-06-10 10:57:20                           │
│ ├─ Reference Price: ₹714.50                               │
│ └─ [View Full Signal]                                      │
│                                                             │
│ BROKER METADATA FINDINGS                                    │
│ ├─ Entry Source: UNKNOWN (broker API didn't provide)       │
│ ├─ Order Tags: ["trading_algo"] (inconclusive)             │
│ ├─ Recommendation: Broker metadata insufficient to prove   │
│ │                 manual vs system entry                   │
│                                                             │
│ NEXT STEPS                                                  │
│ ├─ Status: REQUIRES OPERATOR REVIEW                        │
│ ├─ Action: Operator must decide based on evidence          │
│ │          Position flagged as DO_NOT_TOUCH due to         │
│ │          contradictory evidence. Proceed with caution.   │
│ └─ [View Review Task]                                      │
│                                                             │
│ AUDIT TRAIL                                                 │
│ ├─ Detected: 2026-06-10 11:30 by SCHEDULER                │
│ ├─ Classified: 2026-06-10 11:30 as UNVERIFIED_EXTERNAL    │
│ ├─ Flagged: DO_NOT_TOUCH at classification time            │
│ ├─ Review Created: 2026-06-10 11:31                       │
│ └─ [Show Full Audit Log]                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Features:**
- Evidence breakdown with visual indicators (✅ yes, ❌ no, ⚠️ uncertain)
- Color-coded confidence (green ≥ 90%, yellow 70-89%, orange 50-69%, red < 50%)
- **Explicit DO_NOT_TOUCH indicators if status warrants it**
- Direct link to matched signal
- Broker metadata section (what did broker provide, what's missing?)
- Audit trail with timestamps
- **NO recovery/exit buttons**

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
│ [Assign to Me] [Reassign]                                  │
│                                                              │
│ FILTERS:                                                     │
│ ├─ Status: [All] [My Tasks] [Unassigned] [DO_NOT_TOUCH]   │
│ └─ Priority: [All] [Critical-DO_NOT_TOUCH] [High] [Med]   │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│ PENDING REVIEW TASKS                                        │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ 🔴 NSE:JSWSTEEL (20 qty)                    DO_NOT_TOUCH││
│ │                                                           ││
│ │ Classification: UNVERIFIED_EXTERNAL (System Assessment) ││
│ │ Evidence Score: 70/100 (INFERRED confidence)             ││
│ │ Status: ⚠️  CONTRADICTORY EVIDENCE                      ││
│ │                                                           ││
│ │ Quick Review:                                            ││
│ │ ✅ Signal within time window (8 sec gap)                ││
│ │ ✅ Quantity matches (20 = 20)                           ││
│ │ ✅ Side matches (BUY = BUY)                             ││
│ │ ⚠️  Price gap 0.1% (acceptable)                         ││
│ │ ❌ OMS order missing but signal exists                  ││
│ │ ⚠️  Broker metadata inconclusive                        ││
│ │                                                           ││
│ │ ⚠️  WHY FLAGGED DO_NOT_TOUCH:                           ││
│ │     Evidence points to system entry (signal+qty match)   ││
│ │     BUT OMS order is missing AND broker metadata unclear ││
│ │     → Cannot safely claim MANUAL or SYSTEM              ││
│ │     → CAREFUL REVIEW REQUIRED                           ││
│ │                                                           ││
│ │ [Approve as SYSTEM_MANAGED]                             ││
│ │ [Approve as UNKNOWN_ORIGIN]                             ││
│ │ [Approve as UNVERIFIED_EXTERNAL]                        ││
│ │ [Approve as DO_NOT_TOUCH (keep flagged)]                ││
│ │ [Reject] [Need More Info]                                ││
│ │ [View Full Details]                                      ││
│ └─────────────────────────────────────────────────────────┘│
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ 🟡 NSE:ADANIPORTS (5 qty)                     45%       ││
│ │                                                           ││
│ │ Classification: UNKNOWN_ORIGIN                           ││
│ │ Evidence Score: 45/100 (UNKNOWN confidence)              ││
│ │ Signal Found: NO                                         ││
│ │ Entry Time: 10:15:30                                     ││
│ │ Detected: 1 hour ago                                     ││
│ │                                                           ││
│ │ Quick Review:                                            ││
│ │ ❌ No OMS order (not in system)                         ││
│ │ ❌ No matching signal                                    ││
│ │ ⚠️  Entry 2 hours after last system exit               ││
│ │ ⚠️  Broker metadata doesn't identify entry source       ││
│ │                                                           ││
│ │ ASSESSMENT: Cannot determine origin with confidence      ││
│ │            Could be manual, could be system recovery     ││
│ │            failure, could be data loss. Review required. ││
│ │                                                           ││
│ │ [Approve as UNKNOWN_ORIGIN (keep default)]              ││
│ │ [Approve as UNVERIFIED_EXTERNAL (if evidence present)]  ││
│ │ [Approve as DO_NOT_TOUCH (if uncertain)]                ││
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
- Approval buttons for each classification type (SYSTEM_MANAGED, UNKNOWN_ORIGIN, UNVERIFIED_EXTERNAL, DO_NOT_TOUCH)
- Time-to-review SLA indicators (24h due date)
- Link to full details
- **Explicit warnings for DO_NOT_TOUCH positions**
- **NO recovery/exit/modification buttons**

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
│    ├─ Classification: SYSTEM_MANAGED (operator override)   │
│    ├─ Notes: "Clear signal match, OMS gap likely data loss"│
│    └─ Previous: UNVERIFIED_EXTERNAL (system assessment)    │
│                                                              │
│ 2026-06-10 11:30:30 UTC                                     │
│ └─ 📋 REVIEW_TASK_CREATED                                  │
│    ├─ Task ID: task-xyz789                                  │
│    ├─ Status: DO_NOT_TOUCH (contradictory evidence)        │
│    ├─ Assigned to: john.doe (john@example.com)             │
│    └─ Due: 2026-06-11 11:30:30                             │
│                                                              │
│ 2026-06-10 11:30:15 UTC                                     │
│ └─ 🔍 CLASSIFIED (System)                                   │
│    ├─ Classification: UNVERIFIED_EXTERNAL                   │
│    ├─ Status: DO_NOT_TOUCH (flagged)                        │
│    ├─ Score: 70/100                                         │
│    ├─ Confidence: INFERRED                                  │
│    ├─ Matched Signal: sig-abc123def (73% confidence)       │
│    ├─ Evidence:                                             │
│    │  ├─ Signal time: 10:57:20 (8 sec before broker order) │
│    │  ├─ Qty match: 20 = 20 (exact)                        │
│    │  ├─ Side match: BUY = BUY                             │
│    │  ├─ Price gap: 0.1% (ref ₹714.50 vs entry ₹715.50)   │
│    │  └─ Broker metadata: inconclusive about entry source  │
│    └─ Blocking Issues:                                      │
│       ├─ OMS order missing (likely data loss)              │
│       └─ Contradictory evidence: signal exists but cannot  │
│          be proven via broker metadata                     │
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
- **DO_NOT_TOUCH decisions and reasoning visible**

---

## PART E: EVENT FLOWS

### Event Flow 1: Orphan Detection & Classification

```
Timeline: Every 1-2 minutes (OrphanMonitorScheduler)

┌─────────────────────────────────────────────────────┐
│ SCHEDULER STARTS SCAN                               │
└────────────────┬────────────────────────────────────┘
                 │
                 ↓
        ┌──────────────────────────────┐
        │ Record broker position        │
        │ snapshots for all users       │
        │ (BrokerPositionObservation)  │
        └────────┬─────────────────────┘
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
│ - Flag DO_NOT_TOUCH if       │
│   contradictory evidence     │
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
   ┌─────┴──────────────────────────┐
   │                                │
   ↓                                ↓
(DO_NOT_TOUCH)             (Confidence >= LIKELY)
(or uncertain)             (or sufficient evidence)
   │                                │
   ↓                                ↓
CREATE TASK               CREATE TASK
Notify: CAREFUL REVIEW    Notify: Review needed
Assign HIGH PRIORITY      Assign based on score

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

┌──────────────────────────────────┐
│ OPERATOR VIEWS REVIEW TASK       │
│ (From /orphan-review dashboard)  │
└────────────┬───────────────────┘
             │
             ↓
┌───────────────────────────────┐
│ UI displays:                   │
│ - Orphan details              │
│ - Classification evidence     │
│ - Matched signal (if any)     │
│ - Broker metadata findings    │
│ - DO_NOT_TOUCH warnings       │
└────────────┬─────────────────┘
             │
             ↓
┌───────────────────────────────┐
│ OPERATOR CLICKS [APPROVE]     │
│ Selects classification        │
│ (SYSTEM_MANAGED/UNKNOWN_ORIGIN/│
│  UNVERIFIED_EXTERNAL/DO_NOT_   │
│  TOUCH)                         │
│ Enters notes explaining why    │
└────────────┬─────────────────┘
             │
             ↓
┌───────────────────────────────┐
│ APPROVAL SUBMITTED            │
│ 1. Create OrphanReviewApproval│
│ 2. Update OrphanReviewTask    │
│    status                      │
│ 3. Update DetectedOrphanPos   │
│    status                      │
│ 4. Log audit event            │
│ 5. Publish event              │
└────────────┬─────────────────┘
             │
             ↓
┌───────────────────────────────┐
│ Event: OrphanApprovalSubmitted│
│ Payload:                       │
│ - orphan_id                   │
│ - classification_type         │
│ - operator_id                 │
│ - notes                       │
│ - timestamp                   │
│ - do_not_touch_flag (if set)  │
└────────────┬─────────────────┘
             │
             ↓
┌────────────────────────────────┐
│ Mark position as:              │
│ - REVIEWED (classification ok) │
│ - DO_NOT_TOUCH (if pending)    │
│ Notify system for Phase 2      │
│ planning (if applicable)        │
└────────────────────────────────┘
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
  "classification_type": "UNVERIFIED_EXTERNAL",
  "status": "DO_NOT_TOUCH",
  "confidence_level": "INFERRED",
  "evidence_score": 70,
  "evidence_breakdown": {
    "signal_alignment": 25,
    "quantity_match": 25,
    "side_match": 25,
    "price_alignment": 5,
    "conflict_check": 0,
    "external_metadata_score": 0
  },
  "matched_signal_id": "sig-abc123def",
  "blocking_issues": [
    "OMS order missing",
    "Contradictory evidence: signal exists but broker metadata unclear"
  ],
  "do_not_touch_reason": "Cannot safely determine if SYSTEM vs MANUAL due to conflicting evidence",
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
  "status": "DO_NOT_TOUCH",
  "due_date": "2026-06-11T11:30:00Z",
  "do_not_touch_reason": "Contradictory evidence requires careful review",
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
  "approved_classification": "SYSTEM_MANAGED",
  "classification_before": "UNVERIFIED_EXTERNAL",
  "evidence_score_before": 70,
  "evidence_score_after": 70,
  "do_not_touch_override": true,
  "operator_notes": "Signal match is strong (8 sec gap, qty exact, side match). OMS gap likely data loss. Approving as SYSTEM_MANAGED.",
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
  "rejection_reason": "Needs more broker metadata to classify. Keeping as DO_NOT_TOUCH.",
  "reclassification_triggered": true,
  "new_task_created": "task-new123",
  "actor_id": "operator-001",
  "actor_type": "OPERATOR"
}
```

---

## PART G: DATABASE MIGRATIONS

### Migration Files Required

**V105__create_orphan_detection_tables.sql**
```
- Create broker_position_observations table
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
      cron: "0 * * * * *"  # Every minute (or */2 for every 2 minutes)
      timezone: "Asia/Kolkata"
    
    classification:
      time-window-minutes: 5
      evidence-score-threshold: 50  # Lower threshold, DO_NOT_TOUCH handles uncertainty
      confidence-minimum: "UNKNOWN"  # Default to unknown if insufficient evidence
      default-classification: "UNKNOWN_ORIGIN"  # When evidence missing
    
    review:
      task-due-hours: 24
      enabled: true
      do-not-touch-priority: "HIGH"
    
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
      do-not-touch-alert: true
      review-task-alert: true
```

### Service Dependencies

```
OrphanPositionDetectionService
  ↓ depends on
  ├─ BrokerPositionTruthService
  ├─ BrokerPositionObservationRepository
  ├─ OmsOrderRepository
  ├─ OrphanPositionRepository
  └─ AuditLogService

BrokerPositionSnapshotService
  ↓ depends on
  ├─ BrokerPositionTruthService
  └─ BrokerPositionObservationRepository

OrphanClassificationService
  ↓ depends on
  ├─ StrategySignalRepository
  ├─ OmsExecutionRepository
  ├─ BrokerPositionObservationRepository
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

## PART I: CRITICAL CONSTRAINTS (ENFORCED IN CODE)

### What System NEVER Does:

- ❌ **Never claims position is MANUAL without broker metadata proving it**
- ❌ **Never creates OMS orders for orphaned positions**
- ❌ **Never creates execution records**
- ❌ **Never submits exit orders to broker**
- ❌ **Never modifies position quantities or side**
- ❌ **Never automatically classifies as MANUAL or LIKELY_MANUAL**
- ❌ **Never recovers orphaned positions**
- ❌ **Never assumes anything without evidence**

### Default Behaviors:

- ✅ **Default classification: UNKNOWN_ORIGIN** (when evidence insufficient)
- ✅ **Default status: REVIEW_REQUIRED** (all positions need operator approval)
- ✅ **Default action: DO_NOT_TOUCH** (when contradictory evidence found)
- ✅ **All decisions logged and auditable** (2-year retention)
- ✅ **Operator review required** (before any Phase 2 actions)

---

## PART J: IMPLEMENTATION CHECKLIST

### Code Changes Required

- [ ] Create BrokerPositionSnapshotService
- [ ] Create OrphanPositionDetectionService
- [ ] Create OrphanClassificationService
- [ ] Create EvidenceScoringService (updated for DO_NOT_TOUCH logic)
- [ ] Create OperatorReviewWorkflowService
- [ ] Create AuditLogService
- [ ] Create OrphanMonitorScheduler (updated for 1-2 min frequency)
- [ ] Create StaleReviewTaskCleanupScheduler
- [ ] Create AuditLogArchivalScheduler

### Database Changes Required

- [ ] V105 migration (broker observations + orphan detection tables)
- [ ] V106 migration (review workflow tables)
- [ ] V107 migration (audit log table)
- [ ] Repositories for all entities
- [ ] JPA entities for all tables

### UI Changes Required

- [ ] Orphan Positions Dashboard page (updated for DO_NOT_TOUCH)
- [ ] Orphan Details & Evidence page (updated for broker metadata section)
- [ ] Operator Review Queue page (updated classifications and warnings)
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
- [ ] Unit tests for classification algorithm (DO_NOT_TOUCH logic)
- [ ] Unit tests for scoring algorithm
- [ ] Integration tests with real database
- [ ] UI tests for all pages
- [ ] Audit log tests
- [ ] Scheduler tests

### Configuration Required

- [ ] application.yml properties
- [ ] Feature flags (enable/disable monitoring)
- [ ] Cron schedules (1-2 minute frequency)
- [ ] Time zones
- [ ] Retention policies

### Documentation Required

- [ ] API documentation
- [ ] Operator manual (DO_NOT_TOUCH decision guide)
- [ ] Administrator guide
- [ ] Troubleshooting guide

---

## APPROVAL GATE

**AWAITING APPROVAL BEFORE CODING BEGINS**

This revised plan includes:
- ✅ 6 new services (added BrokerPositionSnapshotService)
- ✅ 6 new entities (added BrokerPositionObservation)
- ✅ 3 new schedulers (updated for 1-2 min frequency)
- ✅ 4 new UI pages (updated classifications and DO_NOT_TOUCH)
- ✅ Complete event flows
- ✅ Audit logging strategy
- ✅ Database migrations
- ✅ Configuration properties
- ✅ Implementation checklist
- ✅ **Critical constraints enforced**
- ✅ **MANUAL classification removed**
- ✅ **UNKNOWN_ORIGIN and UNVERIFIED_EXTERNAL classifications**
- ✅ **DO_NOT_TOUCH status for uncertain positions**
- ✅ **Zero automatic actions in Phase 1**

**Ready to code Phase 1 upon approval.**

**PHASE 2 (Recovery and beyond) will be separate after Phase 1 is validated in production.**

