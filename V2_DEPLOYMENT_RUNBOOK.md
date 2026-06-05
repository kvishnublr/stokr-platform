# 🚀 RELEASE_V2 DEPLOYMENT RUNBOOK
## Complete Step-by-Step Deployment & Rollback Procedures

**Document Version:** 1.0
**Last Updated:** 2026-06-05
**Status:** READY FOR DEPLOYMENT
**Reviewed By:** Tech Lead

---

## 📋 PRE-DEPLOYMENT CHECKLIST

### Code Review
- [ ] Code reviewed by 2+ senior developers
- [ ] All comments resolved
- [ ] No security vulnerabilities (SonarQube: Grade A)
- [ ] No code duplication (< 5%)
- [ ] All TODOs resolved
- [ ] API documentation updated

### Testing
- [ ] Unit tests: 100% passing
- [ ] Integration tests: 100% passing
- [ ] Load tests: 100 concurrent ✅
- [ ] Performance baselines met (p99 < 200ms) ✅
- [ ] Database migrations tested on staging
- [ ] Cache layer tested
- [ ] Broker API integration verified

### Database
- [ ] Migration script reviewed by DBA
- [ ] Backup created and verified (can restore in < 1 hour)
- [ ] Replication lag confirmed < 100ms
- [ ] All indexes verified on staging
- [ ] Schema changes backward compatible

### Infrastructure
- [ ] Blue environment (current v1) stable
- [ ] Green environment provisioned
- [ ] Load balancer tested for traffic switching
- [ ] Monitoring dashboards created
- [ ] Alert rules tested
- [ ] Rollback scripts prepared and tested

### Team & Communication
- [ ] On-call engineer assigned
- [ ] Status page created
- [ ] Customer notification sent
- [ ] Support team briefed
- [ ] Runbook distributed
- [ ] Rollback lead identified

---

## 🔵 BLUE-GREEN DEPLOYMENT STEPS

### Phase 1: Pre-Deployment (T-30 minutes)

**1. Final Verification**
```bash
# Verify green environment is ready
./scripts/health-check.sh --target green
# Expected: All services healthy ✓

# Verify database replication
./scripts/check-replication.sh
# Expected: Replication lag < 100ms ✓

# Verify load balancer connectivity
./scripts/test-lb.sh
# Expected: Both blue and green responding ✓
```

**2. Alert System Ready**
```bash
# Test alert channel (PagerDuty, Slack)
./scripts/test-alerts.sh
# Should receive: "Test alert from Stokr Platform"
```

**3. Monitoring Dashboards**
- [ ] Open 5 monitoring dashboards in tabs
- [ ] Refresh rate: 10 seconds
- [ ] Have alerts page open (for critical alert verification)

### Phase 2: Traffic Cutover (T=0)

**Step 1: Route 10% Traffic to Green**

```bash
# Route 10% of traffic (10 out of 100 traders = ~10 requests/sec)
./scripts/switch-traffic.sh --percentage 10 --target green

# Verify traffic routing
./scripts/verify-routing.sh
# Expected output:
# Blue:  90% (900 traders)
# Green: 10% (100 traders)
```

**Step 2: Monitor for 15 Minutes**

Monitor these metrics on dashboards:
- Error rate (should be < 0.5%, alert if > 2%)
- P99 latency (should be < 200ms, alert if > 300ms)
- Cache hit rate (should be > 90%, alert if < 80%)
- DB connections (should be < 50% of max)
- Memory usage (should be < 60% of max)
- Message queue depth (should be < 100 items)

```bash
# Real-time monitoring script
./scripts/monitor-metrics.sh --duration 15m --sample-interval 5s
```

**If Issues Detected → ROLLBACK IMMEDIATELY**

```bash
# Rollback to 0% green traffic (all to blue)
./scripts/switch-traffic.sh --percentage 0 --target green

# Verify all traffic back on blue
./scripts/verify-routing.sh

# Investigate the issue
./scripts/compare-errors.sh --blue --green
```

**If All Good → Continue to Step 3**

### Step 3: Route 50% Traffic to Green

```bash
# Route 50% of traffic
./scripts/switch-traffic.sh --percentage 50 --target green

# Verify
./scripts/verify-routing.sh
# Expected:
# Blue:  50% (500 traders)
# Green: 50% (500 traders)
```

**Monitor for 15 Minutes (Same Metrics)**

```bash
# Same monitoring as before
./scripts/monitor-metrics.sh --duration 15m --sample-interval 5s
```

**If Issues → Rollback to 0% and investigate**

**If All Good → Continue to Step 4**

### Step 4: Route 100% Traffic to Green

```bash
# Route 100% of traffic to green
./scripts/switch-traffic.sh --percentage 100 --target green

# Verify all traffic on green
./scripts/verify-routing.sh
# Expected:
# Blue:  0% (no traffic)
# Green: 100% (all 100 traders)
```

**Monitor for 1 Hour Continuously**

```bash
# Extended monitoring
./scripts/monitor-metrics.sh --duration 1h --sample-interval 5s

# Also monitor these specific items:
# - Order throughput (orders/min)
# - Signal generation (signals/min)
# - Broker API latency
# - Cache hit rate
```

### Phase 3: Post-Cutover (T+1 hour)

**1. Verify All Systems**

```bash
# Verify all services healthy on green
./scripts/health-check.sh --target green --verbose

# Verify database replication (green reading from blue)
./scripts/check-replication.sh

# Verify traders can place orders
./scripts/smoke-test-trader-flow.sh

# Verify broker connectivity
./scripts/test-broker-connectivity.sh
```

**2. Data Integrity Check**

```bash
# Compare data between blue and green
./scripts/data-integrity-check.sh
# Should report:
# - Order count: ✓ MATCH
# - Signal count: ✓ MATCH
# - User count: ✓ MATCH
# - P&L totals: ✓ MATCH
```

**3. Decommission Blue Environment**

```bash
# Stop blue environment services
./scripts/stop-blue.sh

# Verify blue is stopped
./scripts/verify-routing.sh
# Expected: Green only, Blue offline

# Archive blue data (keep for 7 days as backup)
./scripts/archive-blue.sh --retention 7d
```

**4. Celebrate Success! 🎉**
- [ ] All tests passed
- [ ] All systems healthy
- [ ] Traders confirmed working
- [ ] No data loss
- [ ] No service interruption

---

## 🔴 ROLLBACK PROCEDURES

### Immediate Rollback (< 5 minutes)

**If Critical Issue During Traffic Switch:**

```bash
# STOP ALL TRAFFIC TO GREEN - IMMEDIATE
./scripts/switch-traffic.sh --percentage 0 --target green

# Verify all traffic back on blue
./scripts/verify-routing.sh

# Check error logs
./scripts/show-errors.sh --target green --lines 50

# Notify team
./scripts/alert.sh --severity CRITICAL --message "Rollback initiated"

# Post-mortem
./scripts/capture-diagnostics.sh --target green --output rollback-diagnostics/
```

### Rollback After Go-Live (> 1 hour after switch)

If critical issues detected after green is fully live:

```bash
# Switch back to blue (need to re-enable blue first)
./scripts/start-blue.sh
./scripts/health-check.sh --target blue

# Route all traffic back to blue
./scripts/switch-traffic.sh --percentage 100 --target blue

# Verify
./scripts/verify-routing.sh

# Investigate green logs
./scripts/show-errors.sh --target green --lines 100
```

### Complete Rollback (Database Changes)

If database migrations caused issues:

```bash
# WARNING: This reverses database changes - only if absolutely necessary

# 1. Backup current state
pg_dump -h $DB_HOST -U $DB_USER $DB_NAME > rollback-backup-$(date +%Y%m%d-%H%M%S).sql

# 2. Restore from pre-migration backup
psql -h $DB_HOST -U $DB_USER $DB_NAME < pre-v2-backup.sql

# 3. Verify restoration
./scripts/data-integrity-check.sh

# 4. Update app to point to restored database
./scripts/point-to-previous-db.sh

# 5. Restart green with previous database state
./scripts/restart-green.sh
```

---

## 📊 MONITORING DURING DEPLOYMENT

### Critical Metrics to Watch

```
ERROR_RATE = sum(http_requests_total{status=~"5.."}) / sum(http_requests_total)
  Target: < 0.5% (alert if > 2%)
  
LATENCY_P99 = histogram_quantile(0.99, http_request_duration_seconds)
  Target: < 200ms (alert if > 300ms)
  
CACHE_HIT_RATE = sum(cache_hits) / sum(cache_requests)
  Target: > 90% (alert if < 80%)
  
DB_CONNECTIONS = hikaricp_connections_active
  Target: < 50% of max (alert if > 70%)
  
MEMORY_USAGE = jvm_memory_used_bytes / jvm_memory_max_bytes
  Target: < 60% (alert if > 80%)
  
QUEUE_DEPTH = rabbitmq_queue_messages_ready
  Target: < 100 (alert if > 500)
```

### Dashboard URLs

- **Performance Dashboard:** http://monitoring:3000/d/stokr-performance
- **Infrastructure Dashboard:** http://monitoring:3000/d/stokr-infrastructure
- **Business Metrics:** http://monitoring:3000/d/stokr-business
- **Operational Dashboard:** http://monitoring:3000/d/stokr-operational
- **Alert Status:** http://alertmanager:9093

### Real-Time Logs

```bash
# Watch deployment logs in real-time
tail -f /var/log/stokr/deployment-v2.log

# Watch error logs
tail -f /var/log/stokr/error.log | grep -i "ERROR\|CRITICAL"

# Watch broker integration
tail -f /var/log/stokr/broker.log
```

---

## 🚨 Emergency Contacts

| Role | Name | Phone | Slack |
|------|------|-------|-------|
| Deployment Lead | [Name] | [Phone] | @[User] |
| Tech Lead | [Name] | [Phone] | @[User] |
| DBA | [Name] | [Phone] | @[User] |
| DevOps Lead | [Name] | [Phone] | @[User] |
| VP Engineering | [Name] | [Phone] | @[User] |

**On-Call Rotation:** [Link to on-call schedule]

---

## ✅ POST-DEPLOYMENT VERIFICATION

### 24-Hour Checklist

- [ ] No errors in logs
- [ ] All traders can access platform
- [ ] Orders executing normally
- [ ] Signals generating at expected rate
- [ ] Broker API integration working
- [ ] Database replication in sync
- [ ] All metrics within normal ranges
- [ ] No customer complaints in support channels
- [ ] Run full regression test suite

### 7-Day Verification

- [ ] No memory leaks detected
- [ ] No connection leaks detected
- [ ] Performance metrics stable
- [ ] All data integrity checks passing
- [ ] Customer feedback collected (survey)
- [ ] Costs analyzed (any unexpected spikes?)

### Go-Live Declared ✅

Once all above checks pass, Release_v2 is officially in production:

```bash
# Mark as official release
./scripts/mark-release.sh --version 2.0.0 --status PRODUCTION
```

---

## 🔍 Troubleshooting Guide

### Issue: High Error Rate on Green

```bash
# 1. Check recent errors
./scripts/show-errors.sh --target green --lines 100

# 2. Check for database connection issues
./scripts/test-db-connection.sh --target green

# 3. Check broker connectivity
./scripts/test-broker.sh

# 4. If unknown: Rollback immediately
./scripts/switch-traffic.sh --percentage 0 --target green
```

### Issue: P99 Latency Spike

```bash
# 1. Check database query performance
./scripts/show-slow-queries.sh --duration 5m

# 2. Check cache hit rate
./scripts/show-cache-metrics.sh

# 3. Check message queue depth
./scripts/show-queue-depth.sh

# 4. If unsolvable: Reduce traffic to green
./scripts/switch-traffic.sh --percentage 50 --target green
```

### Issue: Broker API Failures

```bash
# 1. Check broker connectivity
./scripts/test-broker-connectivity.sh

# 2. Check auth tokens
./scripts/show-broker-tokens.sh

# 3. Check API quota usage
./scripts/show-broker-quota.sh

# 4. If broker is down: Mark as degraded mode
./scripts/set-broker-degraded-mode.sh
```

---

## 📝 Deployment Log Template

```
DEPLOYMENT LOG - Release_v2
Date: [Date]
Start Time: [Time]
Deployment Lead: [Name]

T-30min: Pre-deployment checks
  ✓ Code reviewed
  ✓ Tests passing
  ✓ Database ready
  ✓ Infrastructure ready

T+0: Traffic switch begins
  T+0:00 - 10% traffic to green
  [Metrics at 10% - all green]
  T+15:00 - 50% traffic to green
  [Metrics at 50% - all green]
  T+30:00 - 100% traffic to green
  [Metrics at 100% - all green]

T+60min: Deployment complete
  ✓ All services healthy
  ✓ Data integrity verified
  ✓ Traders confirmed working
  ✓ Broker sync verified
  
Status: SUCCESS ✅
```

---

**Questions?** Contact the deployment lead or consult the troubleshooting guide above.

