# CHANGE IMPACT MATRIX
## Scope Reduction to Minimum Viable Implementation

**Objective:** Identify all planned changes, classify by priority, reduce scope to P0 (must-have).

**Question:** What is the absolute minimum to automatically close 1 position when target/stop is hit?

---

## SECTION 1: DATABASE CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **position_exit_audit table** | NEW | Record all exit decisions for audit trail | OmsOrder exists | LOW | DROP TABLE position_exit_audit | Audit logging, compliance trail | Integration test | **P0** |
| ~~position_exit_events table~~ | ~~NEW~~ | ~~Immutable event log~~ | ~~None~~ | ~~LOW~~ | ~~DROP TABLE~~ | ~~Detailed audit~~ | ~~Integration~~ | **P2** |
| ~~portfolio_positions.exit_order_id~~ | ~~MOD~~ | ~~Track exit order on position~~ | ~~portfolio_positions~~ | ~~LOW~~ | ~~ALTER DROP COLUMN~~ | ~~Position metadata~~ | ~~Schema test~~ | **P2** |
| ~~portfolio_positions.exit_state~~ | ~~MOD~~ | ~~Track position state (OPEN/CLOSED/etc)~~ | ~~portfolio_positions~~ | ~~MEDIUM~~ | ~~ALTER DROP COLUMN~~ | ~~State tracking~~ | ~~Schema test~~ | **P2** |
| ~~oms_orders.order_reason~~ | ~~MOD~~ | ~~Track why order was created~~ | ~~oms_orders~~ | ~~LOW~~ | ~~ALTER DROP COLUMN~~ | ~~Order metadata~~ | ~~Schema test~~ | **P1** |
| ~~idx_exit_order_check index~~ | ~~NEW~~ | ~~Fast duplicate detection~~ | ~~oms_orders~~ | ~~LOW~~ | ~~DROP INDEX~~ | ~~Query performance~~ | ~~Query test~~ | **P1** |
| ~~idx_open_positions index~~ | ~~NEW~~ | ~~Fast position loading~~ | ~~portfolio_positions~~ | ~~LOW~~ | ~~DROP INDEX~~ | ~~Query performance~~ | ~~Query test~~ | **P1** |

**Database Summary:**
- **P0 Required:** 1 new table (position_exit_audit)
- **Total P0 lines of SQL:** ~40 lines
- **Deployment time:** 2 minutes

---

## SECTION 2: OMS CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **ExitOrderCreationService** | NEW | Create MARKET order to close position | OrderPlacementService | LOW | DELETE CLASS | Core functionality | Unit + Integration | **P0** |
| **DuplicateExitChecker** | NEW | Prevent multiple exit orders same symbol | OmsOrderRepository | MEDIUM | DELETE CLASS | Safety (prevent over-exit) | Unit + Integration | **P0** |
| ~~PositionExitAuditRepository~~ | ~~NEW~~ | ~~Query position_exit_audit~~ | ~~Database~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Audit queries~~ | ~~Unit test~~ | **P1** |
| ~~OrderLifecycleService modifications~~ | ~~N/A~~ | ~~Already handles idempotency~~ | ~~Existing~~ | ~~LOW~~ | ~~N/A~~ | ~~No changes needed~~ | ~~N/A~~ | **N/A** |

**OMS Summary:**
- **P0 Required:** 2 new services
- **P0 Lines of code:** ~150 lines
- **Deployment time:** <1 second (classes only)
- **Risk:** MEDIUM (duplicate prevention is critical)

---

## SECTION 3: PORTFOLIO CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **PositionMonitoringService** | NEW | Core scheduler + evaluation logic | PortfolioPositionRepository, MarketDataQueryService | MEDIUM | DELETE CLASS | Core functionality | Unit + Integration | **P0** |
| ~~ExitAuditService~~ | ~~NEW~~ | ~~Record audit events~~ | ~~PositionExitAuditRepository~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Audit recording~~ | ~~Unit test~~ | **P1** |
| ~~ExitEvaluationService~~ | ~~NEW~~ | ~~Combine target+stop evaluation~~ | ~~TargetHitEvaluator, StopLossEvaluator~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Decision logic~~ | ~~Unit test~~ | **P0** |
| **TargetHitEvaluator** | NEW | Check if currentPrice >= targetPrice | OmsOrder | LOW | DELETE CLASS | Part of P0 evaluation | Unit test | **P0** |
| **StopLossEvaluator** | NEW | Check if currentPrice <= stopPrice | OmsOrder | LOW | DELETE CLASS | Part of P0 evaluation | Unit test | **P0** |
| ~~PortfolioQueryService modifications~~ | ~~N/A~~ | ~~Already loads positions~~ | ~~Existing~~ | ~~LOW~~ | ~~N/A~~ | ~~No changes needed~~ | ~~N/A~~ | **N/A** |

**Portfolio Summary:**
- **P0 Required:** 4 new services/evaluators
- **P0 Lines of code:** ~400 lines
- **Deployment time:** <1 second
- **Risk:** MEDIUM (core logic must be correct)

---

## SECTION 4: MARKET DATA CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| ~~MarketDataValidator~~ | ~~NEW~~ | ~~Check if price data is fresh~~ | ~~MarketDataQueryService~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Stale data detection~~ | ~~Unit test~~ | **P1** |
| ~~SessionValidator~~ | ~~NEW~~ | ~~Check if market hours are open~~ | ~~HolidayService (if exists)~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Session control~~ | ~~Unit test~~ | **P1** |
| ~~MarketDataQueryService modifications~~ | ~~N/A~~ | ~~Already retrieves prices~~ | ~~Existing~~ | ~~LOW~~ | ~~N/A~~ | ~~No changes needed~~ | ~~N/A~~ | **N/A** |

**Market Data Summary:**
- **P0 Required:** 0 new components (skip validators for v1)
- **Rationale:** Start with "run any time" - add session/stale checks in Phase 2
- **Risk reduction:** Removes complexity, keeps P0 minimal

---

## SECTION 5: SCHEDULING CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **PositionMonitoringScheduler** | NEW | @Scheduled every 30s | Spring Framework | MEDIUM | DELETE CLASS | Core functionality | Integration test | **P0** |
| ~~UserBatchProcessor~~ | ~~NEW~~ | ~~Parallel user processing~~ | ~~Thread pool~~ | ~~MEDIUM~~ | ~~DELETE CLASS~~ | ~~Performance only~~ | ~~Load test~~ | **P2** |
| ~~SchedulingMetrics~~ | ~~NEW~~ | ~~Track cycle performance~~ | ~~Micrometer~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Metrics only~~ | ~~Unit test~~ | **P1** |

**Scheduling Summary:**
- **P0 Required:** 1 scheduler class
- **P0 Lines of code:** ~80 lines
- **Deployment time:** <1 second
- **Interval:** 30 seconds (not optimized, can reduce in Phase 2)
- **Threading:** Sequential (not parallel)

---

## SECTION 6: EVENT & AUDIT CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **position_exit_audit table** | NEW | Audit trail | Database | LOW | DROP TABLE | Compliance | Schema test | **P0** |
| **ExitEvent** | NEW | Domain event for listeners | Spring ApplicationEvent | LOW | DELETE CLASS | Event publication | Unit test | **P0** |
| **PositionExitEventListener** | NEW | Listen for ExitEvent, record audit | ExitEvent, position_exit_audit | LOW | DELETE CLASS | Audit recording | Integration test | **P0** |
| ~~ExitAuditService~~ | ~~NEW~~ | ~~Business logic for audit~~ | ~~PositionExitAuditRepository~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Audit recording~~ | ~~Unit test~~ | **P1** |
| ~~MetricsEventListener~~ | ~~NEW~~ | ~~Listen and publish metrics~~ | ~~ExitEvent, Micrometer~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Metrics only~~ | ~~Unit test~~ | **P1** |

**Event Summary:**
- **P0 Required:** 3 classes (event + listener + table)
- **P0 Lines of code:** ~100 lines
- **Deployment time:** <1 second
- **Rationale:** Minimal audit trail - enough for compliance, not excessive

---

## SECTION 7: MONITORING CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| ~~PositionMonitoringMetrics~~ | ~~NEW~~ | ~~Track exits, errors, latency~~ | ~~Micrometer~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Metrics only~~ | ~~Unit test~~ | **P1** |
| ~~PositionMonitoringHealthIndicator~~ | ~~NEW~~ | ~~Health check endpoint~~ | ~~Spring Boot Actuator~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Health only~~ | ~~Unit test~~ | **P1** |
| ~~Monitoring Dashboard~~ | ~~NEW~~ | ~~Grafana JSON~~ | ~~Prometheus metrics~~ | ~~LOW~~ | ~~DELETE FILE~~ | ~~Visibility only~~ | ~~Manual~~ | **P2** |

**Monitoring Summary:**
- **P0 Required:** 0 new monitoring components
- **Rationale:** v1 can use logs + basic health checks
- **Can add metrics in Phase 1.5 after P0 proves stable

---

## SECTION 8: CONFIGURATION CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **Feature flag: stokr.position.monitor-enabled** | NEW | Enable/disable monitoring | application.properties | LOW | Delete property | Safety (control rollout) | Integration test | **P0** |
| ~~Interval config: stokr.position.monitor-interval-ms~~ | ~~NEW~~ | ~~Control scheduler interval~~ | ~~@Value annotation~~ | ~~LOW~~ | ~~Delete property~~ | ~~Tuning only~~ | ~~Unit test~~ | **P1** |
| ~~Batch size config~~ | ~~NEW~~ | ~~Control user batch processing~~ | ~~@Value annotation~~ | ~~LOW~~ | ~~Delete property~~ | ~~Performance tuning~~ | ~~Unit test~~ | **P2** |
| ~~Price staleness threshold~~ | ~~NEW~~ | ~~Market data freshness requirement~~ | ~~@Value annotation~~ | ~~LOW~~ | ~~Delete property~~ | ~~Data validation~~ | ~~Unit test~~ | **P1** |

**Configuration Summary:**
- **P0 Required:** 1 feature flag
- **P0 Lines of code:** 1 property
- **Deployment time:** <1 second
- **Default value:** true (enabled by default)

---

## SECTION 9: DOMAIN/MODEL CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **ExitReason enum** | NEW | Standardize exit reasons (TARGET_HIT, STOP_LOSS_HIT) | None | LOW | DELETE CLASS | Type safety | Unit test | **P0** |
| **ExitDecision model** | NEW | Immutable exit decision object | ExitReason | LOW | DELETE CLASS | Decision transfer | Unit test | **P0** |
| **ExitEvent class** | NEW | Domain event published on exit | ExitReason | LOW | DELETE CLASS | Event publication | Unit test | **P0** |
| ~~ExitState enum~~ | ~~NEW~~ | ~~Position state tracking~~ | ~~None~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~State management~~ | ~~Unit test~~ | **P1** |
| ~~ExecutionEnvironment enum~~ | ~~NEW~~ | ~~Environment isolation~~ | ~~None~~ | ~~LOW~~ | ~~DELETE CLASS~~ | ~~Environment safety~~ | ~~Unit test~~ | **P1** |

**Domain Summary:**
- **P0 Required:** 3 enums/models
- **P0 Lines of code:** ~50 lines
- **Deployment time:** <1 second
- **Purpose:** Type safety and event passing

---

## SECTION 10: TESTING CHANGES

| Component | New/Mod | Purpose | Dependencies | Risk | Rollback | Impact | Tests | Priority |
|-----------|---------|---------|--------------|------|----------|--------|-------|----------|
| **TargetHitEvaluatorTest** | NEW | Unit test target hit logic | JUnit 5 | LOW | DELETE FILE | Correctness | Required | **P0** |
| **StopLossEvaluatorTest** | NEW | Unit test stop loss logic | JUnit 5 | LOW | DELETE FILE | Correctness | Required | **P0** |
| **DuplicateExitCheckerTest** | NEW | Unit test duplicate prevention | JUnit 5 + Mockito | LOW | DELETE FILE | Safety | Required | **P0** |
| **ExitOrderCreationServiceTest** | NEW | Unit test order creation | JUnit 5 + Mockito | LOW | DELETE FILE | Integration | Required | **P0** |
| **PositionMonitoringServiceTest** | NEW | Integration test full flow | JUnit 5 + @SpringBootTest | LOW | DELETE FILE | E2E | Required | **P0** |
| ~~DuplicatePreventionTest~~ | ~~NEW~~ | ~~Scenario testing~~ | ~~JUnit 5~~ | ~~LOW~~ | ~~DELETE FILE~~ | ~~Edge cases~~ | ~~Optional~~ | **P1** |
| ~~FailureRecoveryTest~~ | ~~NEW~~ | ~~Failure simulation~~ | ~~JUnit 5~~ | ~~LOW~~ | ~~DELETE FILE~~ | ~~Resilience~~ | ~~Optional~~ | **P2** |

**Testing Summary:**
- **P0 Required:** 5 core test classes
- **P0 Test methods:** ~30 test methods
- **Deployment time:** N/A (tests don't deploy)
- **Required for GO-LIVE:** All P0 tests must pass

---

## PRIORITY CLASSIFICATION SUMMARY

### P0: MUST HAVE (Minimum Viable Implementation)

**Database:**
1. position_exit_audit table (audit trail)

**Services:**
2. PositionMonitoringService (core scheduler + evaluation)
3. PositionMonitoringScheduler (@Scheduled every 30s)
4. TargetHitEvaluator (check target)
5. StopLossEvaluator (check stop loss)
6. ExitOrderCreationService (create MARKET order)
7. DuplicateExitChecker (prevent duplicates)
8. PositionExitEventListener (record audit)

**Domain Models:**
9. ExitReason enum (TARGET_HIT, STOP_LOSS_HIT)
10. ExitDecision model (immutable decision)
11. ExitEvent class (domain event)

**Configuration:**
12. Feature flag: stokr.position.monitor-enabled

**Tests:**
13. TargetHitEvaluatorTest
14. StopLossEvaluatorTest
15. DuplicateExitCheckerTest
16. ExitOrderCreationServiceTest
17. PositionMonitoringServiceTest

**Total P0 Components:** 17  
**Total P0 Lines of Code:** ~700 lines Java + ~40 lines SQL  
**Total P0 Test Lines:** ~400 lines  
**Deployment Time:** <5 minutes  

---

### P1: IMPORTANT (High Value, Can Defer)

**Database:**
1. Index: idx_exit_order_check (query performance)
2. OmsOrder.order_reason column (order tracking)

**Services:**
3. ExitAuditService (separate audit logic)
4. PositionMonitoringMetrics (performance metrics)
5. PositionMonitoringHealthIndicator (health check)
6. MarketDataValidator (stale price detection)
7. SessionValidator (market hours check)
8. MetricsEventListener (metrics publication)

**Configuration:**
9. Interval config (scheduling)
10. Price staleness threshold

**Domain:**
11. ExitState enum (state tracking)
12. ExecutionEnvironment enum (environment isolation)

**Tests:**
13. DuplicatePreventionScenarioTest
14. EnvironmentIsolationTest

**Total P1 Components:** 14  
**Value:** Adds robustness, monitoring, validation  
**Can add in:** Phase 1.5-2.0 (after P0 stable)  

---

### P2: NICE TO HAVE (Future Phases)

1. position_exit_events table (detailed event log)
2. portfolio_positions state tracking (redundant)
3. UserBatchProcessor (parallelization)
4. Monitoring Dashboard (Grafana JSON)
5. Advanced metrics/health
6. Session controls (advanced)
7. Stale data protection (advanced)
8. Extensive scenario testing

**Total P2 Components:** 8+  
**Value:** Optimization and advanced features  
**Can add in:** Phase 2+ (when baseline works)  

---

## IMPACT ASSESSMENT

### Change Summary by Priority

| Priority | Components | Risk | Deployment | Rollback |
|----------|-----------|------|-----------|----------|
| **P0** | 17 | MEDIUM | <5 min | <30 sec |
| **P1** | 14 | LOW | <5 min | <30 sec |
| **P2** | 8+ | LOW | <5 min | <30 sec |

### Risk by Priority

**P0 Risks (MEDIUM):**
- Duplicate exit orders (mitigated by DuplicateExitChecker)
- OMS unavailability (handled by exception, retry next cycle)
- Wrong exit prices (unit tests validate)

**P1 Risks (LOW):**
- Metrics publishing failure (non-critical)
- Stale price usage (controlled by validator)
- Market hour violations (session validator)

**P2 Risks (LOW):**
- Over-engineering (doesn't affect core)

### Production Impact

**P0:**
- Users get automatic exits when target/stop hit ✓
- Positions close without manual intervention ✓
- Audit trail recorded for compliance ✓
- Feature flag allows safe rollout ✓
- Can be disabled instantly if issues ✓

**P1:**
- Better visibility (metrics, health)
- Safer operation (validators)
- Easier debugging (better logging)

**P2:**
- Optimization (parallelization)
- Advanced features (AI integration point)

---

## ROLLOUT STRATEGY

### P0 Rollout (Week 1)
```
Day 1: Code + unit tests
Day 2: Integration tests + staging
Day 3-4: Phased production rollout (1% → 10% → 50% → 100%)
Day 5: Stabilization + monitoring
```

### P1 Rollout (Week 2)
```
After P0 stable: Add metrics, validators, health checks
One at a time, monitoring for impact
```

### P2 Rollout (Week 3+)
```
After P0 + P1 stable: Add optimization and advanced features
```

---

## FINAL SCOPE: PHASE 1 P0 ONLY

### Must Build (17 Components)

**Database:**
- position_exit_audit table

**Java Classes (12):**
- PositionMonitoringScheduler
- PositionMonitoringService
- TargetHitEvaluator
- StopLossEvaluator
- ExitOrderCreationService
- DuplicateExitChecker
- PositionExitEventListener
- ExitReason enum
- ExitDecision model
- ExitEvent class
- (2 more if evaluators combined)

**Configuration:**
- 1 feature flag (stokr.position.monitor-enabled)

**Tests (5 classes):**
- TargetHitEvaluatorTest
- StopLossEvaluatorTest
- DuplicateExitCheckerTest
- ExitOrderCreationServiceTest
- PositionMonitoringServiceTest

### Explicitly NOT Building (P1+)

- ❌ Advanced validators (stale data, session)
- ❌ Metrics collection
- ❌ Health indicators
- ❌ Parallelization
- ❌ Extra config options
- ❌ Extended audit tables
- ❌ Dashboards
- ❌ Scenario testing

### Why This Works

✅ **Minimal complexity:** 700 lines core logic  
✅ **Maximum safety:** Duplicates prevented, audit trail recorded  
✅ **Fast deployment:** <5 minutes  
✅ **Easy rollback:** 1 feature flag disables it  
✅ **Can extend:** P1/P2 add cleanly on top  

---

## NEXT: EXACT CODING ORDER FOR P0

*See following section: "PHASE 1 P0 CODING ORDER"*

