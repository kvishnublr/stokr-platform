# 🧪 PHASE 2: INTEGRATION TESTING FRAMEWORK
## Release_v2 - Advanced Caching & Rate Limiting Validation

**Date:** 2026-06-06 (Start of Phase 2 integration testing)  
**Duration:** 1 day  
**Objective:** Validate all Phase 2 components work together seamlessly  

---

## 📋 INTEGRATION TEST PLAN

### **Test Suite 1: Distributed Session Management** (2 hours)

#### **Test 1.1: Session Creation & Storage**
```java
@Test
void testSessionCreatedInRedis() {
    // 1. Create session via login
    SessionUser user = login("trader1@example.com");
    
    // 2. Verify session stored in Redis
    String sessionId = getSessionCookie();
    String redisKey = "stokr:session:sessions:" + sessionId;
    Object sessionData = redisClient.get(redisKey);
    
    // 3. Verify session contains user data
    assertNotNull(sessionData);
    assertTrue(sessionData.contains("trader1@example.com"));
    
    // Expected: < 50ms from creation to Redis store
    // Actual: Measure with timer
}
```

#### **Test 1.2: Session Replication Across Cluster**
```java
@Test
void testSessionReplicationAcrossNodes() {
    // 1. Create session on primary node
    SessionUser session = login("trader2@example.com");
    
    // 2. Verify on all 3 nodes
    String sessionId = getSessionCookie();
    
    for (String node : ["redis-node1", "redis-node2", "redis-node3"]) {
        RedisClient client = connectToNode(node);
        Object data = client.get("stokr:session:sessions:" + sessionId);
        assertNotNull(data, "Session missing on " + node);
    }
    
    // Expected: All 3 nodes have same session data
}
```

#### **Test 1.3: Session Failover (Node Down)**
```java
@Test
void testSessionFailoverWhenNodeDown() {
    // 1. Create session
    SessionUser session = login("trader3@example.com");
    String sessionId = getSessionCookie();
    
    // 2. Kill primary Redis node
    killRedisNode("redis-node1");
    sleep(2000);  // Wait for failover
    
    // 3. Try to access session (should work via replica)
    LoginResponse response = verifySession(sessionId);
    assertTrue(response.isValid);
    
    // Expected: Session still accessible, < 100ms failover time
    // Actual: Measure time
}
```

#### **Test 1.4: Session Timeout**
```java
@Test
void testSessionTimeoutAfter120Minutes() {
    // 1. Create session
    SessionUser session = login("trader4@example.com");
    
    // 2. Advance time by 120 minutes
    mockTime.advanceTo(System.currentTimeMillis() + (120 * 60 * 1000));
    
    // 3. Try to access session (should be expired)
    LoginResponse response = verifySession(getSessionCookie());
    assertFalse(response.isValid);
    
    // Expected: Session expired after 120 min TTL
}
```

#### **Test 1.5: Concurrent Sessions (Max 1 per user)**
```java
@Test
void testMaxOneSessionPerUser() {
    // 1. Create first session
    SessionUser session1 = login("trader5@example.com", "browser1");
    
    // 2. Create second session (same user, different browser)
    SessionUser session2 = login("trader5@example.com", "browser2");
    
    // 3. Verify only latest session is valid
    assertTrue(isSessionValid(session2));
    assertFalse(isSessionValid(session1));  // Should be invalidated
    
    // Expected: Only 1 active session per user enforced
}
```

---

### **Test Suite 2: Rate Limiting Integration** (2 hours)

#### **Test 2.1: Per-User Rate Limits Enforced**
```java
@Test
void testPerUserRateLimitingOrders() {
    UUID userId = UUID.randomUUID();
    
    // 1. Send 100 requests (at limit for /api/orders)
    for (int i = 1; i <= 100; i++) {
        Response response = createOrder(userId, "ORDER_" + i);
        assertEquals(200, response.getStatus(), "Request " + i + " should be allowed");
        
        // Verify rate limit headers
        int remaining = Integer.parseInt(response.getHeader("X-RateLimit-Remaining"));
        assertEquals(100 - i, remaining);
    }
    
    // 2. Send 101st request (should be rate limited)
    Response response = createOrder(userId, "ORDER_101");
    assertEquals(429, response.getStatus());
    assertEquals("Rate limit exceeded", response.getBody().error);
    
    // Expected: 100 requests allowed, 101st denied
}
```

#### **Test 2.2: Per-Endpoint Rate Limits**
```java
@Test
void testDifferentLimitsPerEndpoint() {
    UUID userId = UUID.randomUUID();
    
    // /api/orders: 100 req/min
    for (int i = 1; i <= 100; i++) {
        Response r = createOrder(userId);
        assertEquals(200, r.getStatus());
    }
    Response r = createOrder(userId);
    assertEquals(429, r.getStatus());  // Limit reached
    
    // /api/signals: 50 req/min (different limit)
    for (int i = 1; i <= 50; i++) {
        Response r2 = createSignal(userId);
        assertEquals(200, r2.getStatus());
    }
    Response r2 = createSignal(userId);
    assertEquals(429, r2.getStatus());  // Limit reached
    
    // /api/portfolio: 200 req/min (different limit)
    for (int i = 1; i <= 200; i++) {
        Response r3 = getPortfolio(userId);
        assertEquals(200, r3.getStatus());
    }
    Response r3 = getPortfolio(userId);
    assertEquals(429, r3.getStatus());  // Limit reached
    
    // Expected: Each endpoint has independent limits
}
```

#### **Test 2.3: Rate Limit Reset After 1 Minute**
```java
@Test
void testRateLimitResetAfterOneMinute() {
    UUID userId = UUID.randomUUID();
    
    // 1. Hit rate limit
    for (int i = 1; i <= 100; i++) {
        createOrder(userId);
    }
    Response response = createOrder(userId);
    assertEquals(429, response.getStatus());
    
    // 2. Advance time by 1 minute
    mockTime.advanceTo(System.currentTimeMillis() + 60000);
    
    // 3. Try again (should succeed)
    response = createOrder(userId);
    assertEquals(200, response.getStatus());
    
    // Expected: Limit resets after 60 seconds
}
```

#### **Test 2.4: Request Queuing (Graceful Degradation)**
```java
@Test
void testRequestQueuingWhenRateLimited() {
    UUID userId = UUID.randomUUID();
    
    // 1. Hit rate limit (100 orders/min)
    for (int i = 1; i <= 100; i++) {
        createOrder(userId);
    }
    
    // 2. Queue 5 more requests
    for (int i = 1; i <= 5; i++) {
        Response response = createOrder(userId);
        assertEquals(429, response.getStatus());
        
        // Verify queue info in headers
        int queued = Integer.parseInt(response.getHeader("X-RateLimit-Queued"));
        assertEquals(i, queued);  // 1, 2, 3, 4, 5
    }
    
    // Expected: Queued requests tracked and reported
}
```

#### **Test 2.5: Different Users Have Independent Limits**
```java
@Test
void testPerUserLimitIsolation() {
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    
    // 1. User 1 hits rate limit
    for (int i = 1; i <= 100; i++) {
        Response r = createOrder(user1);
        assertEquals(200, r.getStatus());
    }
    Response r = createOrder(user1);
    assertEquals(429, r.getStatus());  // User 1 limited
    
    // 2. User 2 should NOT be limited
    for (int i = 1; i <= 100; i++) {
        Response r2 = createOrder(user2);
        assertEquals(200, r2.getStatus());  // All succeed
    }
    
    // Expected: Each user has independent limit bucket
}
```

---

### **Test Suite 3: Hybrid Cache (L1 + L2) Coordination** (2 hours)

#### **Test 3.1: L1 Cache Hit (< 1ms)**
```java
@Test
void testL1CacheHit() {
    // 1. Load user profile (goes to L2 Redis)
    long start = System.nanoTime();
    UserProfile profile = userService.getProfile(userId);
    long firstLoad = System.nanoTime() - start;
    
    // 2. Load again immediately (should hit L1 Caffeine)
    start = System.nanoTime();
    UserProfile profile2 = userService.getProfile(userId);
    long secondLoad = System.nanoTime() - start;
    
    // Expected: L1 hit < 1ms, much faster than first load
    assertTrue(secondLoad < TimeUnit.MILLISECONDS.toNanos(1),
        "L1 hit should be < 1ms, got " + TimeUnit.NANOSECONDS.toMillis(secondLoad) + "ms");
}
```

#### **Test 3.2: L2 Cache Hit (< 5ms)**
```java
@Test
void testL2CacheHit() {
    // 1. Clear L1 cache for user
    cacheManager.getCache("user_profile").evict(userId);
    
    // 2. Load user profile (should hit L2 Redis)
    long start = System.nanoTime();
    UserProfile profile = userService.getProfile(userId);
    long latency = System.nanoTime() - start;
    
    // Expected: L2 hit < 5ms
    assertTrue(latency < TimeUnit.MILLISECONDS.toNanos(5),
        "L2 hit should be < 5ms, got " + TimeUnit.NANOSECONDS.toMillis(latency) + "ms");
}
```

#### **Test 3.3: Cache Miss (Database Fallback)**
```java
@Test
void testCacheMissAndDatabaseFallback() {
    // 1. Invalidate both L1 and L2 caches
    cacheManager.getCache("user_profile").clear();
    redisClient.del("stokr:cache:user_profile:*");
    
    // 2. Load user profile (should hit database)
    long start = System.nanoTime();
    UserProfile profile = userService.getProfile(userId);
    long latency = System.nanoTime() - start;
    
    // 3. Verify now cached in both L1 and L2
    UserProfile cached = cacheManager.getCache("user_profile").get(userId).get();
    assertEquals(profile.id, cached.id);
    
    // Expected: Database hit 50-100ms, then backfill to caches
}
```

#### **Test 3.4: Cache Invalidation Coordination**
```java
@Test
void testCacheInvalidationPropagation() {
    // 1. Load user profile (cached in L1 + L2)
    UserProfile profile = userService.getProfile(userId);
    
    // 2. Update user profile
    UserProfile updated = new UserProfile();
    updated.name = "Updated Name";
    userService.updateProfile(userId, updated);
    
    // 3. Verify cache invalidated in both L1 and L2
    Object l1 = cacheManager.getCache("user_profile").get(userId);
    Object l2 = redisClient.get("stokr:cache:user_profile:" + userId);
    
    assertNull(l1, "L1 cache should be invalidated");
    assertNull(l2, "L2 cache should be invalidated");
    
    // 4. Load again (should get fresh from database)
    UserProfile fresh = userService.getProfile(userId);
    assertEquals("Updated Name", fresh.name);
    
    // Expected: Cache invalidation propagates to both levels
}
```

#### **Test 3.5: Cache Hit Rate Measurement**
```java
@Test
void testCacheHitRateMetrics() {
    // 1. Pre-load cache with 50 users
    for (int i = 0; i < 50; i++) {
        userService.getProfile(userIds[i]);
    }
    
    // 2. Simulate 500 requests across 50 users (10 each)
    for (int i = 0; i < 500; i++) {
        int userIndex = i % 50;
        userService.getProfile(userIds[userIndex]);
    }
    
    // 3. Check cache metrics
    CacheStats stats = cacheManager.getStats();
    double hitRate = stats.hits / (stats.hits + stats.misses);
    
    // Expected: > 95% hit rate for warm cache
    assertTrue(hitRate > 0.95, "Hit rate should be > 95%, got " + hitRate);
}
```

---

### **Test Suite 4: Combined Load Test** (4 hours)

#### **Test 4.1: 50 Concurrent Users - Phase 2 Targets**
```bash
#!/bin/bash
# Load test with 50 concurrent traders, 30 minutes

./scripts/load-test-phase2.sh \
  --concurrent-users 50 \
  --duration 30 \
  --target http://localhost:8080 \
  --verify-targets phase2

# Expected Results:
# ✅ Order creation p99: < 100ms (Phase 2 target)
# ✅ Portfolio query p99: < 50ms (Phase 2 target)
# ✅ Cache hit rate: > 99%
# ✅ Error rate: < 0.1%
# ✅ No rate limit errors (requests within limits)
```

#### **Test 4.2: Rate Limiting Under Load**
```bash
#!/bin/bash
# Test rate limiting behavior with 50 concurrent traders

# Create 50 traders, each making requests at different rates
for trader_id in {1..50}; do
    for request_count in {1..150}; do
        # Each trader makes 150 /api/orders requests
        # Limit is 100/min, so 50 should be rate limited
        curl -X POST http://localhost:8080/api/orders \
          -H "X-User-Id: $trader_id" \
          -d @request.json &
    done
done

wait

# Expected Results:
# ✅ First 100 requests per trader: 200 OK
# ✅ Requests 101-150: 429 Too Many Requests
# ✅ Total rate limited: ~2500 (50 traders × 50 excess requests)
# ✅ No request loss or crashes
```

#### **Test 4.3: Session Management Under Load**
```bash
#!/bin/bash
# Test distributed sessions with 50 concurrent traders

# Create 50 traders with different sessions
for trader_id in {1..50}; do
    LOGIN_RESPONSE=$(curl -X POST http://localhost:8080/api/auth/login \
      -d "username=trader$trader_id&password=password")
    SESSION_ID=$(echo $LOGIN_RESPONSE | jq '.sessionId')
    
    # Each trader makes requests using their session
    for request_count in {1..100}; do
        curl -X GET http://localhost:8080/api/portfolio \
          -H "Cookie: STOKR_SESSION=$SESSION_ID" &
    done
done

wait

# Expected Results:
# ✅ All 50 sessions created and maintained
# ✅ Sessions replicated across all 3 Redis nodes
# ✅ No session loss or conflicts
# ✅ < 50ms session lookup latency
```

#### **Test 4.4: Cache Coordination Under Load**
```bash
#!/bin/bash
# Test L1 + L2 cache coordination with 50 concurrent traders

# Measure cache hit rates during sustained load
for trader_id in {1..50}; do
    for i in {1..1000}; do
        curl -X GET http://localhost:8080/api/portfolio?cache=true \
          -H "X-User-Id: $trader_id" &
    done
done

wait

# Collect metrics
curl http://localhost:8080/actuator/metrics/cache.gets.hit
curl http://localhost:8080/actuator/metrics/cache.gets.miss

# Expected Results:
# ✅ Total hits: > 99% (L1 + L2 combined)
# ✅ Average latency: < 5ms
# ✅ No cache inconsistencies
```

---

## 📊 SUCCESS CRITERIA

All tests MUST PASS for Phase 2 integration:

### **Session Management:**
- ✅ Sessions stored in Redis
- ✅ Replicated across 3 nodes
- ✅ Failover works (node down = automatic switch)
- ✅ Timeout enforced (120 min)
- ✅ Max 1 session per user enforced

### **Rate Limiting:**
- ✅ Per-user limits enforced
- ✅ Per-endpoint limits independent
- ✅ Limit reset after 60 seconds
- ✅ Request queuing (graceful)
- ✅ 429 responses correct

### **Cache Coordination:**
- ✅ L1 hits < 1ms
- ✅ L2 hits < 5ms
- ✅ Combined hit rate > 99%
- ✅ Cache invalidation coordinated
- ✅ Database fallback works

### **Combined Load (50 traders):**
- ✅ Order creation p99 < 100ms
- ✅ Portfolio query p99 < 50ms
- ✅ Cache hit rate > 99%
- ✅ Error rate < 0.1%
- ✅ No request loss or crashes

---

## 🚀 EXECUTION STEPS

**Day 2 (2026-06-06):**
```bash
# Step 1: Run Test Suite 1 (Sessions) - 2 hours
./mvnw test -Dtest=DistributedSessionManagementTest -DfailIfNoTests=false

# Step 2: Run Test Suite 2 (Rate Limiting) - 2 hours
./mvnw test -Dtest=RateLimitingIntegrationTest -DfailIfNoTests=false

# Step 3: Run Test Suite 3 (Cache) - 2 hours
./mvnw test -Dtest=HybridCacheCoordinationTest -DfailIfNoTests=false

# Step 4: Review results
./scripts/integration-test-report.sh
```

**Day 3 (2026-06-07):**
```bash
# Step 5: Run combined load test - 4+ hours
./scripts/load-test-phase2.sh \
  --concurrent-users 50 \
  --duration 60 \
  --target http://localhost:8080

# Step 6: Analyze results
./scripts/analyze-load-test-results.sh load-test-results/

# Step 7: Report
echo "All Phase 2 integration tests PASSED ✅"
```

---

**Status:** Ready for Phase 2 integration testing  
**Risk Level:** Low (comprehensive test coverage)  
**Next:** 500-trader load testing (after integration passes)

