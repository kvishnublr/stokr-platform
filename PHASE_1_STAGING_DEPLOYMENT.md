# 🚀 PHASE 1: STAGING DEPLOYMENT GUIDE
## Release_v2 - Prepare for Production Load Testing

**Duration:** 1-2 hours (mostly automated)  
**Date:** 2026-06-05 (Today - Parallel with Phase 2 development)  
**Target:** Validate Phase 1 optimizations with 50-trader load test  

---

## 📋 PRE-DEPLOYMENT CHECKLIST

### **Code Readiness** ✅
- [x] Phase 1 complete (100%)
- [x] All gaps closed
- [x] All tests passing
- [x] Load test scripts ready
- [x] Verification scripts ready

### **Infrastructure Readiness**
- [ ] Staging environment provisioned
- [ ] PostgreSQL with V101 indexes ready
- [ ] Redis (single instance for staging) running
- [ ] Network configured (load balancer, health checks)
- [ ] Monitoring configured (Prometheus, alerts)

---

## 🚀 DEPLOYMENT STEPS

### **STEP 1: Pre-Deployment Verification** (15 min)

```bash
# 1. Verify code is ready
git checkout Release_v2
git pull origin Release_v2

# 2. Verify tests pass
./mvnw clean test
# Expected: All tests pass, 0 failures

# 3. Build application
./mvnw -DskipTests clean package
# Expected: Build succeeds, war/jar created

# 4. Verify migrations exist
ls -la stokr-bootstrap/src/main/resources/db/migration/V101*
# Expected: V101__Release_V2_Optimization_Indexes.sql exists

# 5. Verify configuration
grep -n "hikari" stokr-bootstrap/src/main/resources/application-v2.yml | head -5
# Expected: HikariCP configured with 60 connections
```

### **STEP 2: Backup Staging Database** (10 min)

```bash
# Backup current database state
pg_dump -h staging-db.internal -U postgres staging_db > backup-$(date +%Y%m%d-%H%M%S).sql

# Verify backup size (should be 500MB - 1GB for staging data)
ls -lh backup-*.sql
```

### **STEP 3: Deploy to Staging** (15 min)

```bash
# 1. Stop current staging instance
docker stop stokr-platform-staging || true

# 2. Backup current image
docker tag stokr-platform-staging:latest stokr-platform-staging:backup-$(date +%Y%m%d)

# 3. Build new image with Phase 1 optimizations
docker build \
  --build-arg SPRING_PROFILE=v2 \
  --tag stokr-platform-staging:latest \
  -f Dockerfile .

# 4. Start staging with new image
docker run \
  -d \
  -e SPRING_PROFILES_ACTIVE=v2 \
  -e DB_HOST=staging-db.internal \
  -e REDIS_HOST=staging-redis.internal \
  -p 8080:8080 \
  --name stokr-platform-staging \
  stokr-platform-staging:latest

# 5. Wait for startup
sleep 30

# 6. Verify application started
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

### **STEP 4: Verify Migrations Applied** (10 min)

```bash
# Check if V101 migration was applied
psql -h staging-db.internal -U postgres -d staging_db -c \
  "SELECT version, description, success FROM flyway_schema_history 
   ORDER BY installed_rank DESC LIMIT 5;"

# Expected output:
# version | description | success
# --------|-------------|--------
# 101     | Release_V2_Optimization_Indexes | t
# 100     | create_connection_pool_monitor | t
# 99      | create_auto_detection_monitors | t

# Verify indexes created
psql -h staging-db.internal -U postgres -d staging_db -c \
  "SELECT COUNT(*) as index_count FROM pg_indexes 
   WHERE indexname LIKE 'idx_%';"

# Expected: 15 (or more) indexes

# Verify index performance
psql -h staging-db.internal -U postgres -d staging_db -c \
  "EXPLAIN ANALYZE 
   SELECT * FROM oms_orders 
   WHERE user_id = 'xxx' AND state = 'FILLED' 
   ORDER BY created_at DESC LIMIT 20;"

# Expected: Uses Index Scan (not Seq Scan)
```

### **STEP 5: Verify Application** (15 min)

```bash
# Check health endpoints
curl http://localhost:8080/actuator/health/cacheHealth | jq '.'
# Expected: Redis UP, cache stats

curl http://localhost:8080/actuator/health/db | jq '.'
# Expected: Database UP

# Check metrics available
curl http://localhost:8080/actuator/metrics | jq '.names | length'
# Expected: 200+ metrics

# Verify configuration loaded
curl http://localhost:8080/actuator/env | jq '.propertySources[] | select(.name | contains("application-v2"))'
# Expected: application-v2.yml configuration present

# Verify Redis connection
curl http://localhost:8080/actuator/health/redisHealth 2>/dev/null || echo "Redis health endpoint (custom)"
```

### **STEP 6: Baseline Metrics Capture** (10 min)

```bash
# Capture baseline before load test
./scripts/verify-phase1-targets.sh
# Expected: Baseline metrics in JSON format

# Store baseline for comparison
cp load-test-results/baseline-metrics.json baseline-$(date +%Y%m%d).json
```

---

## 🧪 LOAD TESTING (PARALLEL WITH PHASE 2 DEVELOPMENT)

### **Run 50-Trader Load Test**

```bash
# Terminal 1: Start monitoring
watch -n 1 'curl -s http://localhost:8080/actuator/metrics/hikaricp.connections | jq ".measurements"'

# Terminal 2: Start real-time application logs
tail -f /var/log/stokr/application.log | grep -i "error\|warn\|latency"

# Terminal 3: Run load test
./scripts/load-test-phase1.sh \
  --concurrent-users 50 \
  --duration 60 \
  --target http://localhost:8080

# Monitor output files
tail -f load-test-results/*/metrics-during-test.log
```

### **Load Test Expectations**

Expected results after 60-minute test:

```
✅ Order Creation (p99): < 200ms
✅ Portfolio Query (p99): < 150ms
✅ Cache Hit Rate: > 90%
✅ Error Rate: < 0.5%
✅ DB Connections: < 50 of 60
✅ Memory Usage: < 2GB
✅ Thread Count: < 100
```

If any target is missed:
1. Check error logs: `/var/log/stokr/application.log`
2. Check slow queries: `psql ... -c "SELECT * FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;"`
3. Check cache hit rate: `redis-cli INFO stats`
4. Adjust configuration and retry

---

## ✅ POST-DEPLOYMENT VERIFICATION

### **Immediate (5 min after test)**

```bash
# Run verification script
./scripts/verify-phase1-targets.sh

# Expected output:
# ✅ Order Creation P99 Latency: XXms (Target: <200ms)
# ✅ Portfolio Query P99 Latency: XXms (Target: <150ms)
# ✅ Cache Hit Rate: XX% (Target: >90%)
# ✅ Error Rate: XX% (Target: <0.5%)
# ✅ DB Connections: X/60 (Target: <50)

# Check error logs for any issues
grep ERROR /var/log/stokr/application.log | wc -l
# Expected: < 10 errors in 60 min test
```

### **24-Hour Checklist (after overnight monitoring)**

- [ ] No errors in application logs
- [ ] Performance metrics stable
- [ ] Database replication lag < 100ms
- [ ] Memory usage stable (no leaks)
- [ ] Thread count stable (no leaks)
- [ ] Cache hit rate remains > 90%
- [ ] Error rate remains < 0.5%

### **Generate Report**

```bash
# Collect all results
./scripts/generate-phase1-report.sh load-test-results/

# Output: Phase1-Results-2026-06-05.pdf
# Contains:
# - Latency charts
# - Cache hit rates
# - Error analysis
# - Resource utilization
# - Performance targets met/missed
```

---

## 🔄 ROLLBACK (If Issues Found)

### **Quick Rollback** (< 5 min)

```bash
# Stop new version
docker stop stokr-platform-staging

# Restore old image
docker tag stokr-platform-staging:backup-$(date +%Y%m%d) stokr-platform-staging:rollback
docker run \
  -d \
  -e SPRING_PROFILES_ACTIVE=v1 \
  -p 8080:8080 \
  --name stokr-platform-staging \
  stokr-platform-staging:rollback

# Restore database (if needed)
psql -h staging-db.internal -U postgres -d staging_db < backup-$(date +%Y%m%d-%H%M%S).sql

# Verify rollback
curl http://localhost:8080/actuator/health
```

### **Investigate Issues**

If rollback was needed:
1. Check error logs: `docker logs stokr-platform-staging`
2. Check database: `psql ... -c "SELECT * FROM pg_stat_statements WHERE mean_exec_time > 100;"`
3. File bug report with logs
4. Fix issue in code
5. Retry deployment

---

## 📊 SUCCESS CRITERIA

Phase 1 Staging Deployment is **SUCCESSFUL** when:

✅ **All 4 Performance Targets Met:**
- Order creation p99: < 200ms
- Portfolio query p99: < 150ms
- Cache hit rate: > 90%
- Error rate: < 0.5%

✅ **No Critical Errors:**
- Application logs clean
- No memory leaks
- No thread leaks
- No connection pool exhaustion

✅ **Database Health:**
- All migrations applied
- All indexes created
- Replication working
- Backup successful

✅ **Load Test Complete:**
- 50 concurrent traders
- 60-minute duration
- All metrics captured
- Report generated

---

## 📝 NEXT STEPS

After successful staging deployment:

1. **Document Results**
   - Store load test results
   - Document any issues found
   - Update configuration if needed

2. **Prepare for Phase 2**
   - Continue Phase 2 implementation
   - Integrate Phase 2 with Phase 1
   - Prepare combined deployment

3. **Production Deployment** (When Phase 2 Complete)
   - Use blue-green strategy
   - Gradual traffic shift (10% → 50% → 100%)
   - Real-time monitoring
   - Post-deployment verification

---

## 🎯 TIMELINE

```
06:00 - Start deployment (this guide)
06:15 - Verify code & database
06:30 - Deploy to staging
06:45 - Verify application started
07:00 - Start 60-minute load test
08:05 - Load test complete
08:20 - Verification & reporting
08:30 - Decision: Success or Rollback
```

**Parallel:** Phase 2 implementation continues while staging tests run

---

**Status:** Ready for immediate staging deployment  
**Risk Level:** Low (staging only, rollback available)  
**Go/No-Go:** All systems ready ✅

