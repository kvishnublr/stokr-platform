# Hybrid Implementation Plan - Release_v4 Phase 1

## Overview

**Strategy**: Deploy UI today (with demo labels), integrate real data this week  
**Timeline**: 2 weeks total (5 days UI in production + 5 days real integration)  
**Result**: Fully functional Phase 1 by end of Week 2  

---

## TODAY (Day 1) - 2 Hours

### UI Deployment with Demo Labels

**What**: Deploy Phase 1 UI to development/QA environment with clear "DEMO MODE" warnings

**Changes Made**:
✅ Added demo warning to ServiceHealthPanel.tsx
✅ Added demo warning to QueueMonitoringPanel.tsx  
✅ Added demo warning to SignalLifecyclePanel.tsx

**Warning Message**:
```
⚠️ DEMO MODE
Data is mocked for testing UI/UX. Real monitoring data available 2026-06-17
```

**Status**: READY - All UI changes committed

---

## THIS WEEK - Days 2-5 (Parallel Backend Work)

### Phase 2a: Service Health Checks (Days 2-3)

**What**: Replace hardcoded health data with real service health checks

**Files Created**:
✅ RealServiceHealthChecker.java - HTTP health check client

**Implementation Tasks**:
```
Day 2:
  ✓ Create RestTemplate bean configuration
  ✓ Implement checkStrategyService() - ping port 8081
  ✓ Implement checkExecutionService() - ping port 8082
  ✓ Implement checkRiskService() - ping port 8080
  ✓ Add response time measurement
  ✓ Add timeout handling (> 1s = DEGRADED)
  ✓ Add logging

Day 3:
  ✓ Update MicroservicesHealthController to use RealServiceHealthChecker
  ✓ Remove hardcoded mock data
  ✓ Test with running services
  ✓ Deploy to QA
```

**Expected Result**:
```
Before: 🟢 Strategy Service UP (HARDCODED)
After:  🟢 Strategy Service UP (actual response: 45ms)
```

### Phase 2b: RabbitMQ Integration (Days 3-4)

**What**: Connect to RabbitMQ management API for real queue data

**Files Created**:
✅ RabbitMQManagementClient.java - RabbitMQ HTTP management API client

**Implementation Tasks**:
```
Day 3:
  ✓ Add pom.xml dependency for RabbitMQ HTTP client
  ✓ Implement getQueueStatus(queueName)
  ✓ Implement getAllQueuesStatus()
  ✓ Add error handling for connection failures
  ✓ Add testConnection() method
  
Day 4:
  ✓ Update RabbitMQMonitoringController to use client
  ✓ Remove hardcoded mock data
  ✓ Test with running RabbitMQ
  ✓ Deploy to QA
```

**Configuration Needed**:
```properties
rabbitmq.management.url=http://localhost:15672
rabbitmq.management.username=guest
rabbitmq.management.password=guest
```

**Expected Result**:
```
Before: trading.signals: 247 pending (HARDCODED)
After:  trading.signals: 247 pending (from RabbitMQ API)
```

### Phase 2c: Signal Persistence Setup (Days 4-5)

**What**: Prepare signal event tracking infrastructure (database + service)

**Files Created**:
✅ SignalExecutionEventTracker.java - Signal event logging service

**Implementation Tasks**:
```
Day 4:
  ✓ Create database migration for signal_execution_events table
  ✓ Create SignalExecutionEvent JPA entity
  ✓ Create SignalExecutionEventRepository
  ✓ Uncomment repository injection in SignalExecutionEventTracker
  
Day 5:
  ✓ Add event logging to SignalConsumer.consumeSignal()
  ✓ Add event logging to RiskValidationClient
  ✓ Add event logging to BrokerIntegrationService
  ✓ Add event logging to PositionManagementService
  ✓ Test event logging with real signals
  ✓ Deploy to QA
```

**Database Schema**:
```sql
CREATE TABLE signal_execution_events (
    id BIGSERIAL PRIMARY KEY,
    signal_id VARCHAR(50) NOT NULL,
    event_type VARCHAR(50),  -- GENERATED, QUEUED, RECEIVED, RISK_CHECK, FILLED, etc
    service_name VARCHAR(50),
    duration_ms BIGINT,
    error_message TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (signal_id) REFERENCES signals(id)
);

CREATE INDEX idx_signal_execution_signal_id ON signal_execution_events(signal_id);
CREATE INDEX idx_signal_execution_timestamp ON signal_execution_events(timestamp);
```

**Expected Result**:
```
Before: Signal timeline shows hardcoded 7 steps
After:  Signal timeline shows actual events from database
```

---

## NEXT WEEK - Days 6-8 (Integration)

### Phase 2d: Swap in Real Data (Days 6-8)

**What**: Replace demo data sources with real data, one by one

**Day 6: Service Health Goes Live**
```
UI change: Replace "DEMO MODE" with real status
Update: Remove mock warning from ServiceHealthPanel
Monitor: Watch dashboard for actual health status
```

**Day 7: Queue Monitoring Goes Live**
```
UI change: Replace "DEMO MODE" with real queue data
Update: Remove mock warning from QueueMonitoringPanel
Monitor: Watch queue depth in real-time
```

**Day 8: Signal Tracking Goes Live**
```
UI change: Replace "DEMO MODE" with real signal data
Update: Remove mock warning from SignalLifecyclePanel
Monitor: Track real signals through execution pipeline
```

---

## Code Structure

### What We're Building

```
Day 1 (Today):
├─ UI with DEMO labels ✅ (DONE)
├─ Users see monitoring immediately
└─ Clear label: "not real yet"

Days 2-5:
├─ RealServiceHealthChecker.java ✅ (CREATED)
├─ RabbitMQManagementClient.java ✅ (CREATED)
├─ SignalExecutionEventTracker.java ✅ (CREATED)
├─ Update controllers to use real data
├─ Add database persistence
└─ Test with real signals

Days 6-8:
├─ Remove DEMO labels
├─ Deploy real data sources
└─ Phase 1 COMPLETE
```

---

## Integration Checkpoints

### Day 3 Checkpoint: Service Health
```
✓ ServiceHealthPanel shows real response times
✓ All services respond to health checks
✓ Timeouts handled gracefully
✓ No hardcoded data visible
```

### Day 5 Checkpoint: Queue Monitoring
```
✓ QueueMonitoringPanel shows real queue depths
✓ RabbitMQ management API responding
✓ Pending message counts accurate
✓ Dead-letter queues visible
```

### Day 8 Checkpoint: Signal Tracking
```
✓ SignalLifecyclePanel shows real signal events
✓ Signal events persist to database
✓ Complete timeline from generation to position
✓ Per-step latencies accurate
```

---

## Risk Mitigation

### If Something Breaks

**Service Health Fails**:
- Keep mock data fallback
- Log error, but don't crash dashboard
- Show "Status unavailable - check manually"

**RabbitMQ Connection Fails**:
- Keep mock data fallback
- Show "Queue monitoring unavailable"
- Display last known good data with timestamp

**Signal Persistence Fails**:
- Don't block signal processing
- Log to console for debugging
- Continue with in-memory tracking

---

## Configuration Template

Create `application-phase2.yml`:

```yaml
# Real Service Health Checks
strategy:
  service:
    url: http://localhost:8081
    
execution:
  service:
    url: http://localhost:8082
    
risk:
  service:
    url: http://localhost:8080
    
market:
  data:
    service:
      url: http://localhost:8080

# RabbitMQ Management API
rabbitmq:
  management:
    url: http://localhost:15672
    username: guest
    password: guest
    
# Database
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 20
```

---

## What Gets Deployed When

### Day 1 (Today)
```
deploy-v4-ui-demo
├─ All 3 panels with DEMO labels
├─ Mock data endpoints continue
└─ "Real data available 2026-06-17"
```

### Day 5
```
deploy-v4-real-health-and-queues
├─ Real service health checks
├─ Real queue monitoring
├─ Still have DEMO label (less than 24h away)
└─ Database migration applied
```

### Day 8
```
deploy-v4-phase1-complete
├─ Remove all DEMO labels
├─ Real signal tracking
├─ Real service health
├─ Real queue monitoring
└─ Phase 1 FULLY FUNCTIONAL
```

---

## Testing Checklist

### Day 3: Service Health Testing
- [ ] Strategy service health check returns in < 100ms
- [ ] Execution service health check returns in < 200ms (2 replicas)
- [ ] Risk service health check returns in < 50ms
- [ ] Dashboard shows response times
- [ ] Dashboard handles service DOWN gracefully
- [ ] Dashboard handles timeout (> 5s) as DOWN

### Day 5: Queue Monitoring Testing
- [ ] All 4 queues visible
- [ ] Pending counts accurate
- [ ] Consumer counts accurate
- [ ] Dead-letter queue shows correctly
- [ ] Can expand queue to see details
- [ ] Auto-refresh works (5-second interval)

### Day 8: Signal Tracking Testing
- [ ] Can search for signal by ID
- [ ] Timeline shows all 7+ steps
- [ ] Per-step latencies visible
- [ ] Total latency calculated
- [ ] Order ID shows when available
- [ ] Handles missing signals gracefully

---

## Success Criteria

✅ Phase 1 Complete When:
1. All services show real health data (no mocks)
2. All queues show real RabbitMQ data (no mocks)
3. All signals show real execution events (from database)
4. All DEMO labels removed
5. All tests pass
6. No errors in logs
7. Dashboard responsive under load

---

## Timeline Summary

| Day | Focus | Status | Deployment |
|-----|-------|--------|------------|
| 1 | UI Demo | ✅ DONE | deploy-v4-ui-demo |
| 2-3 | Service Health | IN PROGRESS | |
| 3-4 | Queue Monitoring | PLANNED | |
| 4-5 | Signal Persistence | PLANNED | deploy-v4-real-health-and-queues |
| 6 | Health Live | PLANNED | |
| 7 | Queues Live | PLANNED | |
| 8 | Signals Live | PLANNED | deploy-v4-phase1-complete |

**Total**: 8 days (2 calendar weeks)  
**Result**: Fully functional Phase 1 with real monitoring

---

## What Happens After Phase 1?

Once Phase 1 is complete (with real data), we can immediately start Phase 2-5:

- **Phase 2**: Database persistence & WebSocket real-time
- **Phase 3**: Latency analysis & historical trending
- **Phase 4**: Service alerts & auto-remediation
- **Phase 5**: Advanced analytics & predictions

But Phase 1 will be SOLID FOUNDATION for everything else.

---

## File Locations

### Created Files (Ready to Integrate)

✅ Demo Labels:
- stokr-ui/src/components/admin/microservices/ServiceHealthPanel.tsx
- stokr-ui/src/components/admin/microservices/QueueMonitoringPanel.tsx
- stokr-ui/src/components/admin/microservices/SignalLifecyclePanel.tsx

✅ Real Integration (To be integrated Days 2-5):
- stokr-bootstrap/src/main/java/com/stokr/bootstrap/health/RealServiceHealthChecker.java
- stokr-bootstrap/src/main/java/com/stokr/bootstrap/queue/RabbitMQManagementClient.java
- stokr-bootstrap/src/main/java/com/stokr/bootstrap/signal/SignalExecutionEventTracker.java

### To Create (Days 2-5)

- Database migration: V111__signal_execution_events.sql
- SignalExecutionEvent.java (JPA entity)
- SignalExecutionEventRepository.java (JPA repository)
- Update RealServiceHealthChecker usage in MicroservicesHealthController
- Update RabbitMQManagementClient usage in RabbitMQMonitoringController
- Update SignalExecutionEventTracker usage in SignalConsumer, RiskValidationClient, BrokerIntegrationService

---

## Conclusion

This hybrid approach gives you:
✅ Monitoring UI available today (Day 1)
✅ Real data integrated gradually (Days 2-5)
✅ Phase 1 fully complete by Day 8
✅ Parallel work (no blocking)
✅ Low risk (demo labels prevent confusion)
✅ User feedback shapes integration (Days 1-5)

**Status**: Ready to deploy. All UI changes committed. Integration files created.
