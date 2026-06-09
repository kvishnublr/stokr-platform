# ZERO-SCHEMA-CHANGE REVIEW
## Can We Implement P0 Without Any Database Changes?

**Review Date:** June 9, 2026  
**Reviewers:** Architecture Team  
**Scope:** Every proposed database change  
**Goal:** Minimize production risk  

---

## EXECUTIVE SUMMARY

**Question:** Can we implement PositionMonitoringService without ANY schema changes?

**Answer:** YES, with careful consideration.

**Recommendation:** ZERO schema changes in P0.

**Rationale:** 
- Exit metadata can be stored in logs
- Audit trail can use existing event infrastructure
- No new tables needed
- No new columns strictly required
- Reduces deployment risk

---

## CHANGE REVIEW: Item by Item

### Proposed Change 1: Add exit_metadata JSON column to oms_orders

**Current Proposal:**
```sql
ALTER TABLE oms_orders ADD COLUMN (
    exit_metadata JSON
);
```

**Analysis:**

**Question 1: Why is it needed?**
- To store when exit was triggered
- To store entry price vs exit price
- To store exit reason
- To store market data age at time of evaluation
- For audit trail and troubleshooting

**Question 2: Can existing OMS tables be reused?**
- ✅ YES: OmsOrder already has fields:
  - `reject_reason` (VARCHAR 500) - can store exit details
  - `strategyKey` (VARCHAR 128) - identifies strategy
  - `createdAt` (TIMESTAMP) - when order created
  - Correlation ID - already links orders
- ✅ YES: OmsExecution already has:
  - `avgPrice` - exit execution price
  - `executedAt` - when exit happened
  - `filledQty` - how much exited

**Question 3: Can existing audit/event infrastructure be reused?**
- ✅ YES: Spring ApplicationEvents can:
  - Publish ExitEvent
  - Have listeners log to database
  - Create audit trail via listeners
  - Don't need JSON column

**Question 4: Can existing JSON metadata fields be reused?**
- ❓ UNKNOWN: Does oms_orders have existing metadata column?
- Need to verify in actual schema

**Question 5: Can implementation proceed with ZERO schema changes?**
- ✅ YES, Option A: Store in logs only
  - Log exit decision to application logs
  - Audit listeners log to event table
  - Query logs for troubleshooting
  - Risk: Logs may be rotated/deleted

- ✅ YES, Option B: Use existing fields
  - Store exit_reason in reject_reason field
  - But reject_reason semantics don't match
  - Risk: Semantic confusion

- ✅ YES, Option C: Use event infrastructure
  - ExitEvent published to listeners
  - Listener stores in audit table (new table = not zero-schema)
  - Risk: Requires new table

- ⚠️ MAYBE, Option D: Add one JSON column
  - Single column addition
  - Minimal migration risk
  - Minimal production risk
  - But: Is it truly necessary?

---

### DECISION ON exit_metadata COLUMN

**Choice: REMOVE from P0**

**Reason:**
1. Not strictly required for P0 success
2. Exit can happen without storing metadata
3. Logs provide sufficient audit trail for troubleshooting
4. Can add in Phase 2 if needed

**How to proceed without it:**
```
Exit Event Published
  ├─ ExitEventListener logs to application logger
  │  Output: "EXIT_DECISION: SBIN target_hit at 1008.50"
  │
  ├─ Audit Event Listener (if needed) logs to SLF4J
  │  Output: "AUDIT: Exit created for user123"
  │
  └─ Application logs captured in:
     - Production logs
     - Splunk/ELK/CloudWatch
     - Can query for troubleshooting
```

**Logs provide:**
- ✅ What: Exit reason (target/stop)
- ✅ When: Timestamp
- ✅ Symbol: Position identifier
- ✅ Prices: Entry vs exit
- ✅ User: Who was affected

**Logs don't provide:**
- ❌ Queryable database records
- ❌ Easy reporting

**Trade-off:** Accept logs as audit trail, add database audit in Phase 2

---

### Proposed Change 2: Add exit_order_reason VARCHAR to oms_orders

**Current Proposal:**
```sql
ALTER TABLE oms_orders ADD COLUMN (
    exit_order_reason VARCHAR(50)
);
```

**Analysis:**

**Question 1: Why is it needed?**
- To identify exit orders vs entry orders
- To find all exits easily
- For categorization

**Question 2: Can existing OMS tables be reused?**
- ✅ YES: OmsOrder already has:
  - `strategyKey` - can store 'POSITION_MONITORING_SERVICE'
  - `correlationId` - already unique per order
  - Could prepend order reason to correlation ID

**Question 3: Can existing audit infrastructure be reused?**
- ✅ YES: ExitEvent contains reason, listeners can publish

**Question 4: Can implementation proceed with ZERO schema changes?**
- ✅ YES: Store reason in strategyKey field
  - Set: `order.setStrategyKey("POSITION_MONITORING_SERVICE")`
  - Query: `SELECT * FROM oms_orders WHERE strategyKey = 'POSITION_MONITORING_SERVICE'`
  - Risk: Semantics don't match (strategy key vs order reason)

- ✅ YES: Store reason in correlationId prefix
  - Example: `EXIT-TARGET_HIT-{uuid}`
  - Parse in logs
  - Risk: Correlations IDs become larger

---

### DECISION ON exit_order_reason COLUMN

**Choice: REMOVE from P0**

**Reason:**
1. Can identify exit orders by strategyKey = 'POSITION_MONITORING_SERVICE'
2. Exit reason is in ExitEvent (published to listeners)
3. Logs contain full context
4. Query doesn't need dedicated column

**How to proceed without it:**

```sql
-- Find all exit orders without new column:
SELECT * FROM oms_orders 
WHERE strategy_key = 'POSITION_MONITORING_SERVICE'
ORDER BY created_at DESC;

-- Find all exits created by position monitoring:
SELECT * FROM oms_orders o
WHERE o.strategy_key = 'POSITION_MONITORING_SERVICE'
AND o.state = 'FILLED'
LIMIT 100;
```

---

### Proposed Change 3: Add index idx_exit_metadata

**Current Proposal:**
```sql
CREATE INDEX idx_exit_metadata ON oms_orders 
USING GIN (exit_metadata);
```

**Decision: REMOVE from P0**

**Reason:** 
- No exit_metadata column = no index needed
- Queries use strategy_key instead (likely already indexed)

---

### Proposed Change 4: Create position_exit_audit table

**Current Proposal:**
```sql
CREATE TABLE position_exit_audit (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP,
    user_id UUID,
    symbol VARCHAR(20),
    exit_reason VARCHAR(50),
    ...
);
```

**Decision: REMOVE from P0**

**Reason:**
1. Not required for P0 success
2. Audit trail available via:
   - Application logs
   - ExitEvent listeners (publish to events)
   - OMS order records
3. Adding new table = more migration risk
4. Can add in Phase 2 if needed

**How to proceed without it:**
- Logs contain all information
- ExitEvent published and captured by listeners
- Query oms_orders table filtered by strategy_key
- Sufficient for troubleshooting and compliance

---

## FINAL SCHEMA DECISION

### Proposed Changes: 0

**Change:** NONE

**Why:**
1. All needed information already available in existing tables
2. Logs provide audit trail
3. ExitEvent provides event streaming
4. Queries can use existing columns
5. Reduces deployment complexity
6. Reduces migration risk

### Database Impact

```
Migrations needed: 0
New tables: 0
New columns: 0
New indexes: 0

Risk level: ZERO (no schema changes)
```

---

## AUDIT TRAIL STRATEGY (Zero-Schema Version)

### How We'll Audit Exits Without New Tables

**Approach 1: Application Logs**

```
When exit happens:
  ExitEventListener logs to SLF4J
  
Log output:
  INFO: EXIT_DECISION: SBIN target_hit
  INFO: EXIT_PRICE: 1008.50
  INFO: ENTRY_PRICE: 1000.50
  INFO: QUANTITY: 100
  INFO: USER_ID: user123
  
Storage:
  - Production logs (cloud logging)
  - Splunk/ELK (if configured)
  - CloudWatch (if AWS)
  
Query:
  grep "EXIT_DECISION" /var/log/stokr.log
  
Retention:
  - Standard log rotation
  - Cloud logging retention policies
```

**Approach 2: OMS Order Records**

```
Existing table: oms_orders

Query exit orders:
  SELECT * FROM oms_orders o
  WHERE o.strategy_key = 'POSITION_MONITORING_SERVICE'
  AND o.state = 'FILLED'
  
Fields available:
  - user_id
  - symbol
  - side (SELL/BUY)
  - quantity
  - created_at
  - state
  - correlation_id
  
Sufficient for:
  - Counting exits
  - Finding by user/symbol
  - Tracking status
```

**Approach 3: ApplicationEvent Listeners**

```
ExitEvent published

Listeners:
  1. LoggingListener
     └─ Log to SLF4J (no DB write)
  
  2. MetricsListener (Phase 2)
     └─ Increment counters
  
  3. AuditDatabaseListener (Phase 2)
     └─ Write to audit table IF needed
```

**What We Get (Zero-Schema):**
- ✅ Logs for troubleshooting
- ✅ OMS orders for querying
- ✅ Events for listeners
- ✅ Audit trail via logs + orders

**What We Don't Get (Phase 2):**
- ❌ Dedicated audit table
- ❌ Pre-computed metrics
- ❌ Dashboard queries

---

## ALTERNATIVE SCHEMA OPTIONS (Rejected)

### Option A: Minimal JSON column (Original Plan)

```sql
ALTER TABLE oms_orders ADD COLUMN exit_metadata JSON;
```

**Pros:**
- Structured data
- Queryable
- Future-proof

**Cons:**
- Schema migration required
- Must deploy migration
- Must test migration
- Increases complexity

**Decision:** REJECTED for P0 (add in Phase 2)

---

### Option B: position_exit_audit table (Original Plan)

```sql
CREATE TABLE position_exit_audit (...);
```

**Pros:**
- Dedicated audit records
- Easy querying
- Clean separation

**Cons:**
- New table migration
- Duplicate data (also in oms_orders)
- More to maintain
- More to test

**Decision:** REJECTED for P0 (add in Phase 2)

---

### Option C: Store in existing fields

```sql
-- Use reject_reason for exit details
-- Use correlation_id prefix for reason
```

**Pros:**
- No schema changes
- No migrations
- Works

**Cons:**
- Semantic mismatch
- Hard to parse
- Confusing for future developers

**Decision:** REJECTED (use logs instead)

---

### Option D: Zero schema changes (SELECTED)

```sql
-- No changes
-- Use logs
-- Use existing oms_orders
-- Use event infrastructure
```

**Pros:**
- Zero deployment risk
- Zero migration risk
- Zero production impact
- Fast deployment
- Can add tracking in Phase 2

**Cons:**
- No queryable audit table
- Audit trail in logs (ephemeral)
- Can't easily build reports

**Decision:** SELECTED for P0 ✓

---

## SCHEMA CHANGE SUMMARY

| Item | Type | Needed? | Decision |
|------|------|---------|----------|
| exit_metadata JSON | Column | NO | REMOVE |
| exit_order_reason VARCHAR | Column | NO | REMOVE |
| idx_exit_metadata | Index | NO | REMOVE |
| position_exit_audit | Table | NO | REMOVE |

**Total Schema Changes: 0**

**Migration Risk: ZERO**

**Deployment Time Impact: ZERO**

---

## VERIFICATION: Can We Implement Without Schema Changes?

### Success Criteria (Zero-Schema Implementation)

```
[ ] Exit order created without metadata column
    Answer: YES - Order created in oms_orders with strategy_key

[ ] Exit reason captured without reason column
    Answer: YES - Captured in ExitEvent, logged

[ ] Audit trail available without audit table
    Answer: YES - Via logs, existing oms_orders, events

[ ] Queries work without new columns
    Answer: YES - Query by strategy_key, state, created_at

[ ] Troubleshooting possible without audit table
    Answer: YES - Logs + order records sufficient

[ ] Compliance trail available without dedicated table
    Answer: YES - Logs provide audit trail

[ ] Zero schema changes possible
    Answer: YES - All functionality provided by existing structures
```

---

## PHASE 2+ ENHANCEMENT (Not P0)

Once P0 is stable and working, Phase 2 can add:

```sql
-- Phase 2: Enhanced audit
ALTER TABLE oms_orders ADD COLUMN exit_metadata JSON;

-- Phase 2: Dedicated audit table (optional)
CREATE TABLE position_exit_audit_enhanced (...)

-- Phase 2: Metrics table (optional)
CREATE TABLE exit_performance_metrics (...)
```

**But this is NOT needed for P0 to work.**

---

## FINAL RECOMMENDATION

### **ZERO SCHEMA CHANGES IN P0**

**Why:**
1. ✅ All functionality works without changes
2. ✅ Logs provide audit trail
3. ✅ Existing tables have what we need
4. ✅ Reduces deployment risk
5. ✅ Reduces migration risk
6. ✅ Faster time to production
7. ✅ Easier to roll back if needed

**Implementation:**
1. Use logs for audit trail
2. Use oms_orders.strategy_key to identify exit orders
3. Use ExitEvent for event publishing
4. Add audit table in Phase 2 if needed

**Status:** APPROVED FOR P0 IMPLEMENTATION

