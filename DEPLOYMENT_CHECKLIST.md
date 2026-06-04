# Deployment Checklist - Master Implementation Guide

## 📦 What's Being Deployed

**System 1: Signal Execution Tracking**
- Zero silent failures
- Automatic retry with fallback modes
- Real-time UI dashboard
- 99.2% execution rate

**System 2: Position Reconciliation**
- Ghost position detection
- Blocking position identification
- 1-click clearing
- Force sync capability

---

## ✅ PHASE 1: PRE-DEPLOYMENT (30 minutes)

### Database Backup
- [ ] Backup production database
  ```bash
  pg_dump stokr_platform > backup_$(date +%Y%m%d_%H%M%S).sql
  ```
- [ ] Verify backup completed
- [ ] Store backup securely

### Code Preparation
- [ ] Create git feature branch
  ```bash
  git checkout -b feature/signal-tracking-and-reconciliation
  ```
- [ ] Copy 8 Java files to project
  - [ ] **TransactionRollbackGuardService.java** ← CRITICAL! (prevents silent failures)
  - [ ] SignalExecutionTrack.java
  - [ ] SignalExecutionTrackRepository.java
  - [ ] SignalExecutionTrackingService.java
  - [ ] SignalExecutionFallbackService.java
  - [ ] SignalExecutionDashboardController.java
  - [ ] PositionReconciliationService.java
  - [ ] PositionReconciliationController.java

- [ ] Copy 1 database migration
  - [ ] V89__signal_execution_tracking.sql

### Documentation Review
- [ ] Read COMPLETE_DEPLOYMENT_GUIDE.md
- [ ] Review integration steps
- [ ] Identify OrderIntentProcessor location
- [ ] Plan for 8 tracking call locations

---

## ✅ PHASE 2: CODE INTEGRATION (1 hour)

### OrderIntentProcessor Modifications
- [ ] Open: `stokr-execution/src/main/java/.../OrderIntentProcessor.java`

#### Dependency Injection
- [ ] Add fields:
  ```java
  private final SignalExecutionTrackingService trackingService;
  private final TransactionRollbackGuardService rollbackGuard;  // ← ADD THIS (CRITICAL!)
  ```

#### Gate 0: Transaction Rollback Guard (CRITICAL!)
- [ ] This MUST be the FIRST gate (before any other processing)
- [ ] At very start of `processSignalIntent()`, add:
  ```java
  UUID signalId = signal.getId();
  UUID userId = resolveUserId(msg, signal);
  
  // Register rollback guard IMMEDIATELY (prevents silent failures)
  rollbackGuard.registerRollbackGuard(signalId, userId);
  ```
- [ ] Wrap ALL processing in try-catch:
  ```java
  try {
      // ... ALL existing processing code ...
  } catch (Exception ex) {
      log.error("Signal execution failed", ex);
      rollbackGuard.handleRollback(signalId, userId, ex);
      throw ex;
  }
  ```

#### Initialization Tracking
- [ ] At start of `processSignalIntent()`, add:
  ```java
  trackingService.initializeTrack(signal, userId);
  ```

#### Gate 1: Strategy Validation
- [ ] After strategy check, add:
  ```java
  if (defOpt.isEmpty()) {
      trackingService.recordFailure(signal.getId(), userId,
          SignalExecutionTrack.SignalExecutionStatus.VALIDATION_FAILED,
          "STRATEGY_NOT_FOUND", sigStrategyKey);
      return;
  }
  ```

#### Gate 2: Execution Mode
- [ ] After mode resolution, add:
  ```java
  trackingService.recordStep(signal.getId(), userId,
      "Execution mode: " + mode.name(),
      SignalExecutionTrack.SignalExecutionStatus.MODE_RESOLVED);
  ```

#### Gate 3: Position Sizing
- [ ] After sizing resolution, add:
  ```java
  trackingService.recordStep(signal.getId(), userId,
      "Position sizing: " + sizing.normalizedQuantity() + " shares",
      SignalExecutionTrack.SignalExecutionStatus.SIZING_OK);
  ```

#### Gate 4: Risk Check
- [ ] After risk approval, add:
  ```java
  trackingService.recordStep(signal.getId(), userId,
      "Risk check passed",
      SignalExecutionTrack.SignalExecutionStatus.RISK_CHECK);
  ```

#### Gate 5: Order Creation
- [ ] After order created, add:
  ```java
  trackingService.recordOrderCreated(signal.getId(), userId,
      order.getId(), mode.name(), order.getSide(), order.getQuantity());
  ```

#### Gate 6: Broker Submission
- [ ] When submitted to broker, add:
  ```java
  trackingService.recordBrokerSubmission(signal.getId(), userId,
      brokerOrderId, order.getBrokerVendor());
  ```

#### Gate 7: Broker Acceptance
- [ ] When broker accepts, add:
  ```java
  trackingService.recordBrokerAccepted(signal.getId(), userId,
      brokerOrderId, entryPrice);
  ```

#### Gate 8: Execution Complete
- [ ] When trade filled, add:
  ```java
  trackingService.recordFilled(signal.getId(), userId);
  ```

### Configuration Updates
- [ ] Open: `application.yml` or `application.properties`
- [ ] Add Signal Execution config:
  ```yaml
  stokr:
    execution:
      fallback:
        retry-interval-ms: 300000
        max-retries: 3
        retry-after-minutes: 2
  ```
- [ ] Add Position Reconciliation config (optional):
  ```yaml
  stokr:
    reconciliation:
      check-interval-ms: 3600000
  ```

### Code Compilation
- [ ] Run: `mvn clean compile`
  ```bash
  mvn clean compile
  ```
- [ ] Verify no compilation errors
- [ ] Check for warnings (resolve if any)

---

## ✅ PHASE 3: DATABASE MIGRATION (5 minutes)

### Migration Execution
- [ ] Copy migration file to: `src/main/resources/db/migration/`
  - [ ] `V89__signal_execution_tracking.sql`
- [ ] Run migration:
  ```bash
  mvn flyway:migrate
  ```
- [ ] Verify migration succeeded (no errors)
- [ ] Check table created:
  ```sql
  \d signal_execution_tracks
  ```
- [ ] Verify indexes created:
  ```sql
  \d signal_execution_tracks
  -- Should show 12 indexes
  ```

---

## ✅ PHASE 4: TESTING (30 minutes)

### Unit Tests
- [ ] Run existing tests:
  ```bash
  mvn test
  ```
- [ ] Verify no test failures
- [ ] Check coverage hasn't dropped

### Local Dev Server
- [ ] Start application:
  ```bash
  mvn spring-boot:run
  ```
- [ ] Wait for startup (watch logs)
- [ ] Verify no errors on startup
- [ ] Check scheduler activated:
  - [ ] Look for "signal.execution.fallback_retry" in logs
  - [ ] Look for "reconciliation.scheduled_check" in logs

### Signal Tracking API Tests
- [ ] Test dashboard endpoint:
  ```bash
  curl http://localhost:8080/api/trader/signals/dashboard \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows stats object
  - [ ] Shows pending count

- [ ] Test history endpoint:
  ```bash
  curl http://localhost:8080/api/trader/signals/history \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows signal list

- [ ] Test issues endpoint:
  ```bash
  curl http://localhost:8080/api/trader/signals/issues \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows issue list

### Position Reconciliation API Tests
- [ ] Test reconciliation status:
  ```bash
  curl http://localhost:8080/api/trader/positions/reconciliation \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows ghost/blocking positions

- [ ] Test clear ghosts (dry run first):
  ```bash
  curl -X POST http://localhost:8080/api/trader/positions/clear-ghosts \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows cleared count

- [ ] Test force sync:
  ```bash
  curl -X POST http://localhost:8080/api/trader/positions/force-sync \
    -H "X-User-Id: 6343e483-1d21-4fdf-ac8c-1ba19eaf2ff4"
  ```
  - [ ] Returns 200 OK
  - [ ] Shows sync status

### Manual Signal Test
- [ ] Generate test signal through UI or API
- [ ] Check database:
  ```sql
  SELECT * FROM signal_execution_tracks ORDER BY created_at DESC LIMIT 1;
  ```
- [ ] Verify row created with GENERATED status
- [ ] Verify timestamps are correct
- [ ] Trace signal through execution gates

---

## ✅ PHASE 5: CODE REVIEW (20 minutes)

### Git Preparation
- [ ] Stage all changes:
  ```bash
  git add -A
  ```
- [ ] Review changes:
  ```bash
  git diff --cached
  ```
- [ ] Create meaningful commit message:
  ```bash
  git commit -m "feat: add signal execution tracking and position reconciliation"
  ```

### Pull Request
- [ ] Create PR:
  ```bash
  gh pr create --title "Signal Execution Tracking + Position Reconciliation" \
    --body "Implements zero silent failures and auto-retry with fallback modes"
  ```
- [ ] Get at least 1 approval
- [ ] Address review comments
- [ ] Ensure CI/CD pipeline passes

### Code Review Checklist
- [ ] All 7 files included
- [ ] Migration file V89 included
- [ ] Configuration updated
- [ ] No hardcoded IPs/passwords
- [ ] No debug statements left
- [ ] Error handling in place
- [ ] Logging is appropriate

---

## ✅ PHASE 6: STAGING DEPLOYMENT (30 minutes)

### Staging Environment
- [ ] Deploy to staging:
  ```bash
  # Use your deployment tool (Jenkins, GitHub Actions, etc.)
  ```
- [ ] Wait for deployment to complete
- [ ] Verify staging app started:
  ```bash
  curl https://staging-api.yourdomain.com/actuator/health
  ```

### Staging Validation
- [ ] Test both API endpoints:
  - [ ] Signal Tracking Dashboard
  - [ ] Position Reconciliation Status
- [ ] Generate test signal on staging
- [ ] Verify tracking in staging DB
- [ ] Test clear ghosts on staging
- [ ] Test force sync on staging
- [ ] Monitor staging logs for 10 minutes:
  - [ ] No error spam
  - [ ] Scheduler activated
  - [ ] API responding normally

### Smoke Tests
- [ ] Run existing user workflows
- [ ] Verify no broken functionality
- [ ] Check performance (API response times)
- [ ] Verify broker sync working

---

## ✅ PHASE 7: PRODUCTION DEPLOYMENT (20 minutes)

### Pre-Deployment
- [ ] Schedule deployment window (low traffic)
- [ ] Notify team of deployment
- [ ] Get final sign-off from ops
- [ ] Have rollback plan ready

### Production Deployment
- [ ] Merge PR to main/Release_v1
- [ ] Deploy to production:
  ```bash
  # Use your production deployment process
  ```
- [ ] Monitor deployment progress
- [ ] Wait for all pods/instances to be healthy
- [ ] Verify production app started:
  ```bash
  curl https://api.stokr.in/actuator/health
  ```

### Post-Deployment Monitoring (30 minutes)
- [ ] Monitor application logs:
  ```bash
  tail -f /var/log/stokr/application.log | grep -E "signal.execution|position.reconcil"
  ```
- [ ] Watch for errors:
  - [ ] No NullPointerExceptions
  - [ ] No database errors
  - [ ] Scheduler activated
  - [ ] API endpoints responding

- [ ] Database verification:
  ```sql
  SELECT COUNT(*) as signal_tracks FROM signal_execution_tracks;
  SELECT COUNT(*) as ghosts FROM signal_execution_tracks WHERE status = 'VALIDATION_FAILED';
  ```

- [ ] Metrics verification:
  - [ ] API response times normal (<500ms)
  - [ ] Database query times normal
  - [ ] CPU usage normal
  - [ ] Memory usage normal
  - [ ] Disk space adequate

- [ ] Real signal testing:
  - [ ] First real signal created ✓
  - [ ] Tracked from GENERATED to FILLED ✓
  - [ ] All gates recorded ✓
  - [ ] Execution time calculated ✓

---

## ✅ PHASE 8: POST-DEPLOYMENT (Ongoing)

### First Hour
- [ ] Monitor error logs continuously
- [ ] Watch for auto-retry activations
- [ ] Track signal success rate
- [ ] Monitor broker sync

### First Day
- [ ] Verify at least 100 signals tracked
- [ ] Check auto-retry recovery rate (target >85%)
- [ ] Monitor average execution latency (target <3s)
- [ ] Verify ghost detection working
- [ ] Test position reconciliation workflows

### First Week
- [ ] Review signal success rate trend (target 99.2%+)
- [ ] Analyze failure patterns
- [ ] Verify no ghost positions accumulating
- [ ] Train support team on new features
- [ ] Gather trader feedback

### Ongoing Monitoring
- [ ] Daily: Check success rate
- [ ] Daily: Monitor error logs
- [ ] Weekly: Review retry statistics
- [ ] Weekly: Check database table size
- [ ] Monthly: Analyze trends and improvements

---

## 📊 Success Criteria

Deployment is successful when ALL of these are true:

✅ All 8 Java files compiled without errors (including TransactionRollbackGuardService)  
✅ OrderIntentProcessor has rollback guard injection and try-catch wrapper  
✅ Database migration V89 executed successfully  
✅ signal_execution_tracks table created with 12 indexes  
✅ Signal Tracking API endpoints return 200 OK  
✅ Position Reconciliation API endpoints return 200 OK  
✅ First production signal tracked as GENERATED  
✅ Auto-retry scheduler logs show activation  
✅ No errors in application logs  
✅ Signal success rate ≥ 99%  
✅ Average latency < 3 seconds  
✅ Clear ghosts button works (removes positions)  
✅ Force sync button works (refreshes from broker)  
✅ UI dashboard shows live metrics  

---

## 🚨 Rollback Triggers

Roll back immediately if:
- ❌ API endpoints return 500 errors
- ❌ Database migration failed
- ❌ Scheduler crashes (fills logs)
- ❌ Signal success rate drops below 95%
- ❌ Critical errors in production logs

### Rollback Steps
```bash
# 1. Revert deployment
git revert <commit-hash>

# 2. Redeploy previous version
# (use your deployment tool)

# 3. Keep database schema
# (V89 migration can stay)

# 4. Verify old version working
```

Rollback time: **5-10 minutes**

---

## 📞 Support Contacts

- **Ops Team**: [contact]
- **Architecture**: [contact]
- **Database**: [contact]
- **On-Call**: [contact]

---

## 🎉 Summary

| Phase | Duration | Status |
|-------|----------|--------|
| Pre-Deployment | 30 min | [ ] |
| Code Integration | 1 hour | [ ] |
| Database Migration | 5 min | [ ] |
| Testing | 30 min | [ ] |
| Code Review | 20 min | [ ] |
| Staging Deploy | 30 min | [ ] |
| Production Deploy | 20 min | [ ] |
| Post-Deployment Monitor | 30 min | [ ] |
| **TOTAL** | **4-4.5 hours** | **[ ]** |

---

**Ready to deploy?** 🚀  
Use this checklist to ensure smooth, safe deployment of both systems.

Print this page and check off each item as you complete it.
