# 🚀 PARALLEL EXECUTION GUIDE
## Phase 1 Staging + Phase 2 Integration - EXECUTING NOW

**Start Time:** 2026-06-05 (Immediate)  
**Expected Completion:** 2026-06-08  
**Go-Live:** 2026-06-10  

---

## 📋 PARALLEL EXECUTION SETUP

### **TERMINAL 1: PHASE 1 STAGING DEPLOYMENT** (2-3 hours)

```bash
# Follow: PHASE_1_STAGING_DEPLOYMENT.md
# Timeline:
06:00 - Start deployment (Step 1-2: 25 min)
06:25 - Deploy to staging (Step 3: 15 min)
06:40 - Verify app started (Step 4-5: 15 min)
06:55 - Verify migrations (Step 4: 10 min)
07:05 - Capture baseline (Step 6: 10 min)
07:15 - START 60-MINUTE LOAD TEST
08:15 - Load test complete
08:30 - Verify all 4 targets met
08:45 - Report results
```

**Critical Tasks:**
1. ✅ Verify V101 indexes created
2. ✅ Verify HikariCP 60 connections
3. ✅ Run 50-trader load test
4. ✅ Confirm all 4 targets:
   - Order creation p99 < 200ms
   - Portfolio query p99 < 150ms
   - Cache hit rate > 90%
   - Error rate < 0.5%

**Success Criteria:**
- ✅ All 4 performance targets met
- ✅ No critical errors in logs
- ✅ Load test completes successfully
- ✅ Database replication working

---

### **TERMINAL 2: PHASE 2 INTEGRATION** (2-3 days, parallel with Terminal 1)

```bash
# Day 1 (Today - While Phase 1 tests run):
# Task 1: Distributed Session Management (2-3 hours)
# Task 2: HTTP Interceptor Registration (1 hour)

# Day 2 (2026-06-06):
# Task 3: Integration Testing Setup (4-5 hours)
# Task 4: Combined component testing (3-4 hours)

# Day 3 (2026-06-07):
# Task 5: 500-trader load testing (6-8 hours)
# Task 6: Performance optimization if needed (2-3 hours)
```

**Phase 2 Remaining Tasks:**

1. **Distributed Session Management** (1 day)
   ```java
   DistributedSessionConfiguration.java
   - Configure Spring Session with Redis
   - Session replication across cluster
   - Sticky session support (optional)
   - Session invalidation procedures
   
   Expected: Sessions survive node failure
   ```

2. **HTTP Interceptor Integration** (1 day)
   ```java
   - Register RateLimitingInterceptor in WebMvcConfigurer
   - Add rate limit headers to responses
   - Configure per-endpoint limits
   - Test graceful degradation
   
   Expected: Per-user rate limiting active
   ```

3. **Integration Testing** (1 day)
   ```
   - Test L1 + L2 cache coordination
   - Test rate limiting with concurrent load
   - Test cache warming on startup
   - Test failover scenarios
   
   Expected: All components work together
   ```

4. **500-Trader Load Test** (1 day)
   ```
   - 500 concurrent traders
   - 60-120 minute test duration
   - Monitor cluster health
   - Verify all Phase 2 targets:
     * Order creation p99 < 100ms
     * Portfolio query p99 < 50ms
     * Cache hit rate > 99%+
     * Error rate < 0.1%
   
   Expected: All targets met at 500-trader scale
   ```

---

## 📊 PARALLEL TIMELINE

```
TODAY (2026-06-05):
┌─────────────────────────────────────────────────┐
│ TERMINAL 1: Phase 1 Staging (2-3 hours)         │
│ 06:00-08:45 - Deploy, load test, verify         │
│ ✅ Validates Phase 1 optimizations               │
└─────────────────────────────────────────────────┘
      ↓ (while test runs)
┌─────────────────────────────────────────────────┐
│ TERMINAL 2: Phase 2 Integration (start now)     │
│ 06:00-08:00+ - Session management config        │
│ 08:00+ - HTTP interceptor registration          │
│ ✅ Phase 2 integration begins                    │
└─────────────────────────────────────────────────┘

DAY 2-3 (2026-06-06 to 2026-06-07):
┌─────────────────────────────────────────────────┐
│ TERMINAL 2 CONTINUES: Integration & Testing     │
│ - Component integration testing                  │
│ - 500-trader load testing                        │
│ - Performance optimization                       │
│ ✅ Phase 2 completion                            │
└─────────────────────────────────────────────────┘

RESULT BY 2026-06-08:
✅ Phase 1: Validated with load tests
✅ Phase 2: Integration complete
✅ Both ready for production deployment

GO-LIVE (2026-06-09 to 2026-06-10):
- Phase 1 production (blue-green)
- Phase 2 production (blue-green)
- 500-trader system LIVE ✅
```

---

## ✅ PHASE 1 STAGING (TERMINAL 1) - EXECUTE NOW

### **Quick Start (Copy-Paste Ready)**

```bash
#!/bin/bash
# PHASE_1_STAGING_EXECUTION.sh

set -e

echo "=== PHASE 1 STAGING DEPLOYMENT ==="
echo "Starting: $(date)"

# STEP 1: Pre-deployment verification (5 min)
echo ""
echo "STEP 1: Pre-deployment verification..."
cd /path/to/stokr-platform
git checkout Release_v2
git pull origin Release_v2

# STEP 2: Backup database (10 min)
echo ""
echo "STEP 2: Backing up staging database..."
BACKUP_FILE="backup-$(date +%Y%m%d-%H%M%S).sql"
pg_dump -h staging-db.internal -U postgres staging_db > "$BACKUP_FILE"
echo "Backup created: $BACKUP_FILE"

# STEP 3: Deploy to staging (15 min)
echo ""
echo "STEP 3: Deploying to staging..."
docker build -t stokr-platform-staging:latest .
docker stop stokr-platform-staging || true
docker run -d \
  -e SPRING_PROFILES_ACTIVE=v2 \
  -e DB_HOST=staging-db.internal \
  -e REDIS_HOST=staging-redis.internal \
  -p 8080:8080 \
  --name stokr-platform-staging \
  stokr-platform-staging:latest

echo "Waiting for application startup (30s)..."
sleep 30

# STEP 4: Verify application (10 min)
echo ""
echo "STEP 4: Verifying application..."
curl http://localhost:8080/actuator/health
echo ""
echo "✅ Application started"

# STEP 5: Verify migrations (10 min)
echo ""
echo "STEP 5: Verifying V101 migration..."
psql -h staging-db.internal -U postgres -d staging_db -c \
  "SELECT COUNT(*) FROM pg_indexes WHERE indexname LIKE 'idx_%';"

echo ""
echo "=== READY FOR LOAD TEST ==="
echo "Start Time: $(date)"
echo ""
echo "Run load test:"
echo "  ./scripts/load-test-phase1.sh"
echo ""
```

### **What to Monitor During Phase 1 Test:**

```bash
# Terminal 1A: Watch metrics
watch -n 5 'curl -s http://localhost:8080/actuator/metrics/hikaricp.connections | jq ".measurements"'

# Terminal 1B: Watch logs
tail -f /var/log/stokr/application.log | grep -i "error\|warn\|latency"

# Terminal 1C: Run load test
./scripts/load-test-phase1.sh
```

### **Expected Phase 1 Results:**

```
✅ Order Creation (p99):      < 200ms
✅ Portfolio Query (p99):     < 150ms
✅ Cache Hit Rate:            > 90%
✅ Error Rate:                < 0.5%
✅ DB Connections:            < 50 of 60
✅ No memory leaks
✅ No thread leaks
```

---

## 🔧 PHASE 2 INTEGRATION (TERMINAL 2) - START NOW

### **Task 1: Distributed Session Management** (3-4 hours)

Create `DistributedSessionConfiguration.java`:

```java
@Configuration
@EnableSpringHttpSession
public class DistributedSessionConfiguration {

    @Bean
    public LettuceConnectionFactory connectionFactory() {
        return new LettuceConnectionFactory();
    }

    // Sessions stored in Redis cluster
    // Automatically replicated across nodes
    // Survives node failure
}
```

Register it in `application-v2.yml`:
```yaml
spring:
  session:
    store-type: redis
    redis:
      namespace: stokr:session
    timeout: 120m
```

**Expected:**
- Sessions stored in Redis
- Replicated across cluster
- < 50ms session lookup
- No session loss on failover

---

### **Task 2: HTTP Interceptor Registration** (2-3 hours)

Update `WebMvcConfigurer`:

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitingInterceptor rateLimitingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitingInterceptor)
            .addPathPatterns("/api/**");
    }
}
```

**Expected:**
- Rate limiting active on all /api endpoints
- Per-user limits enforced
- 429 responses for rate-limited users
- Metrics reporting in place

---

### **Task 3: Integration Testing** (4-6 hours)

Create integration tests:

```java
// Test L1 + L2 cache coordination
@Test
void testHybridCacheCoordination() {
    // 1. Verify L1 hit
    // 2. Verify L2 backfill
    // 3. Verify combined hit rate
}

// Test rate limiting integration
@Test
void testRateLimitingIntegration() {
    // 1. Send 100 requests (within limit)
    // 2. Send 101st request (should be 429)
    // 3. Verify queuing behavior
}

// Test cluster failover
@Test
void testClusterFailover() {
    // 1. Kill one Redis node
    // 2. Verify cluster continues working
    // 3. Verify data integrity
}
```

**Expected:**
- All components work together
- No integration issues
- Cluster handles failovers
- Performance targets met

---

### **Task 4: 500-Trader Load Test** (6-8 hours)

```bash
# Enhanced load test for 500 traders
./scripts/load-test-phase2.sh \
  --concurrent-users 500 \
  --duration 120 \
  --target http://localhost:8080

# Expected results:
# ✅ Order creation p99 < 100ms
# ✅ Portfolio query p99 < 50ms
# ✅ Cache hit rate > 99%
# ✅ Error rate < 0.1%
# ✅ All 500 traders active simultaneously
```

---

## 📊 SYNCHRONIZATION POINTS

### **Sync Point 1: Phase 1 Test Complete (08:45 TODAY)**
```
✅ Terminal 1: Phase 1 load test results reviewed
✅ Terminal 2: Session management config complete

Decision Point:
- If Phase 1 PASS ✅ → Continue both as planned
- If Phase 1 FAIL ❌ → Debug Phase 1, Phase 2 continues
```

### **Sync Point 2: Phase 2 Integration Complete (2026-06-08)**
```
✅ Terminal 1: Phase 1 validated & ready for prod
✅ Terminal 2: Phase 2 integration complete

Result:
- Both phases ready
- 500-trader system complete
- Production deployment scheduled
```

### **Sync Point 3: Production Go-Live (2026-06-10)**
```
✅ Phase 1 deployed to production
✅ Phase 2 deployed to production
✅ 500-trader system LIVE

Expected Status:
- 500 concurrent traders supported
- Order creation < 100ms p99
- 99%+ cache hit rate
- < 0.1% error rate
```

---

## 🎯 SUCCESS CRITERIA

### **Phase 1 Staging (Terminal 1):**
- ✅ All 4 performance targets met
- ✅ Load test completes without errors
- ✅ No database issues
- ✅ All metrics captured

### **Phase 2 Integration (Terminal 2):**
- ✅ All components integrated
- ✅ Integration tests passing
- ✅ 500-trader load test success
- ✅ All Phase 2 targets met

### **Combined:**
- ✅ Both phases ready by 2026-06-08
- ✅ Zero integration issues
- ✅ Confidence for production deployment
- ✅ 2026-06-10 go-live on schedule

---

## 🚀 START NOW!

```bash
# TERMINAL 1 (Phase 1):
cd /path/to/stokr-platform
# Follow PHASE_1_STAGING_DEPLOYMENT.md steps

# TERMINAL 2 (Phase 2):
cd /path/to/stokr-platform
# Start with distributed session configuration
# Create DistributedSessionConfiguration.java
# Continue with integration tasks
```

**Status: BOTH TRACKS READY TO EXECUTE** ✅

Go ahead with parallel execution!

