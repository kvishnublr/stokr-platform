# ⚡ PHASE 1 PERFORMANCE TUNING GUIDE
## Release_v2 - Parameter Optimization & Capacity Planning

**Last Updated:** 2026-06-05  
**For:** DevOps, Performance Engineers, Architects  

---

## 🎯 BASELINE CONFIGURATION

**Current Settings (Optimized for 100 traders):**

```yaml
# Database
HIKARI_MAX_POOL_SIZE: 60
HIKARI_MIN_IDLE: 15
HIKARI_CONNECTION_TIMEOUT_MS: 15000

# Cache
CACHE_TTL_MS: 1800000  # 30 min default
REDIS_MAX_CONNECTIONS: 50

# Server
TOMCAT_MAX_THREADS: 100
TOMCAT_ACCEPT_COUNT: 100

# Batching
BATCH_SIZE: 20
FETCH_SIZE: 50
```

---

## 📊 TUNING MATRIX

| Scenario | Load | Adjustment | Expected | Risk |
|----------|------|-----------|----------|------|
| **Light** | <20 traders | Default config | 150ms p99 | LOW |
| **Normal** | 20-50 traders | Default config | 200ms p99 | LOW |
| **Heavy** | 50-100 traders | Config below | 200ms p99 | MEDIUM |
| **Peak** | >100 traders | Phase 2 + Redis cluster | 200ms p99 | HIGH |

---

## 🔧 TUNING PARAMETERS

### **1. DATABASE CONNECTION POOL**

**Current:** `maximum-pool-size: 60`

**When to Increase:**
- Error: "Cannot get a resource, pool error: exhausted"
- Monitor: `hikaricp.connections.active` > 50 consistently
- Calculation: `Traders * Queries/Trader/Sec * Query Duration`

**Example:**
```
50 traders * 5 queries/sec * 0.1 sec avg = 25 connections needed
Use 60 (50% headroom) or 80 (100% headroom)
```

**Tuning Guide:**
```yaml
# For 50 traders
maximum-pool-size: 60
minimum-idle: 15

# For 100 traders
maximum-pool-size: 80
minimum-idle: 20

# For 200+ traders (Phase 2)
maximum-pool-size: 100
minimum-idle: 25

# Connection timeout
connection-timeout: 15000  # 15 sec (don't change unless specific issue)
```

**Monitoring:**
```bash
# Check utilization
watch -n 5 'curl -s http://localhost:8080/actuator/metrics/hikaricp.connections | jq ".measurements"'

# Ideal: < 50% utilization most of the time
# Alert if: > 70% for 5+ min
```

---

### **2. CACHE TTL OPTIMIZATION**

**Current Settings:**
```
portfolio_exposure:    5 min
position_summary:     10 min
user_profile:         30 min
risk_limits:          60 min
market_data:           1 min
signal_confidence:     2 min
execution_status:      2 min
```

**Tuning Strategy:**

**Low TTL (1-5 minutes):**
- Use for: Real-time data (market data, execution status)
- Hit rate: 50-70% (frequent updates)
- Memory: Low (data changes often, gets evicted)
- When to use: Data changes every few seconds

**Medium TTL (10-30 minutes):**
- Use for: User data, portfolio positions, strategy config
- Hit rate: 80-90% (changes during trading session)
- Memory: Medium
- When to use: Data relatively stable during session

**High TTL (60+ minutes):**
- Use for: Risk limits, user profile, compliance data
- Hit rate: 95%+ (rarely changes)
- Memory: High (stays cached long)
- When to use: Data is very stable

**Optimization Approach:**

```bash
# 1. Monitor cache hit rates
watch -n 10 'redis-cli INFO stats | grep hits'

# 2. Analyze hit patterns
redis-cli --csv SLOWLOG GET 100 | grep -i "get"

# 3. If hit rate < 80%: increase TTL
# 4. If hit rate > 95%: can decrease TTL to save memory
# 5. If memory > 80%: decrease TTL or add eviction
```

**Recommended Adjustments:**

```yaml
# If cache hit rate LOW (< 75%)
position_summary: 20 min      # from 10 min
portfolio_exposure: 10 min    # from 5 min

# If cache memory HIGH (> 85%)
position_summary: 5 min       # from 10 min
user_profile: 15 min          # from 30 min

# If same trader, multiple queries (normal case)
Keep defaults: Hit rate will be 85-90%
```

---

### **3. REDIS MEMORY MANAGEMENT**

**Current:** `50 max connections, no memory limit`

**Configuration:**

```bash
# Check current settings
redis-cli CONFIG GET maxmemory
redis-cli CONFIG GET maxmemory-policy
redis-cli INFO memory

# Set memory limit (example: 2GB)
redis-cli CONFIG SET maxmemory 2147483648  # 2GB in bytes
redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

**Memory Calculation:**

```
Approx size per cache entry:
- User profile: 500 bytes
- Position (10 symbols): 2 KB
- Portfolio exposure: 1 KB

Example 50 traders:
- Profiles: 50 * 500 bytes = 25 KB
- Positions: 50 * 10 * 2 KB = 1 MB
- Portfolio: 50 * 1 KB = 50 KB
Total: ~1.1 MB per 50 traders + overhead = ~100 MB for 50 traders

Recommended: 500 MB for < 100 traders
            1-2 GB for 100-500 traders
            5+ GB for 500+ traders
```

**Eviction Policy Options:**

```bash
# LRU (Least Recently Used) - default, recommended
redis-cli CONFIG SET maxmemory-policy allkeys-lru

# LFU (Least Frequently Used) - better for real-world patterns
redis-cli CONFIG SET maxmemory-policy allkeys-lfu

# Don't evict (fail on OOM) - only for stable workloads
redis-cli CONFIG SET maxmemory-policy noeviction
```

---

### **4. THREAD POOL OPTIMIZATION**

**Current:**
```
Tomcat threads: 100 max (was 30)
ForkJoinPool (strategy): 30 threads
RabbitMQ consumers: 5 min, 20 max
```

**Tuning:**

```yaml
# For 50 traders (default)
max-threads: 100

# For 100+ traders
max-threads: 150

# For 200+ traders (Phase 2)
max-threads: 200

# Accept queue size
accept-count: 100  # Don't change unless specific issue
```

**Calculation:**
```
Threads needed = (Avg request duration * Requests/sec) + buffer
Example: 0.2 sec * 500 req/sec = 100 threads + 20 buffer = 120 threads
```

**Monitoring:**
```bash
# Check active threads
curl http://localhost:8080/actuator/metrics/jvm.threads.live | jq '.measurements[0].value'

# Should be: < 80 most of the time
# Alert if: > 100 for 5+ min (thread leak or spike)
```

---

### **5. BATCH PROCESSING TUNING**

**Current:**
```yaml
BATCH_SIZE: 20        # Per batch
FETCH_SIZE: 50        # Rows per fetch
DEFAULT_BATCH_FETCH_SIZE: 20

# Strategy batching
SYMBOL_BATCH_SIZE: 100
CONFIDENCE_BATCH_SIZE: 50
OUTCOME_BATCH_SIZE: 100
```

**When to Increase:**
- Large dataset operations (backtests, bulk exports)
- High memory available
- Larger batch = fewer DB round trips

**When to Decrease:**
- Memory constraints
- Need lower latency (smaller batches = faster response)
- Large objects in batch

**Tuning Guide:**

```yaml
# Memory constrained (< 2GB heap)
BATCH_SIZE: 10
FETCH_SIZE: 25

# Normal (2-4GB heap)
BATCH_SIZE: 20        # Current
FETCH_SIZE: 50

# Memory plenty (> 4GB heap)
BATCH_SIZE: 50
FETCH_SIZE: 100
```

---

### **6. PAGINATION OPTIMIZATION**

**Current Default:** `20 items per page`

**Tuning:**

```yaml
# Default page size
default-page-size: 20

# Maximum page size allowed
max-page-size: 100

# For UI list views
default-page-size: 20

# For API exports
max-page-size: 500  # Be careful with memory

# For batch operations
batch-page-size: 1000  # But process in smaller chunks
```

**Recommendation:**
```
- Keep default at 20 (good for UI, not too much memory)
- Allow max 100 (for power users)
- Never allow > 1000 (OOM risk)
```

---

### **7. QUERY TIMEOUT CONFIGURATION**

**Add to application-v2.yml:**

```yaml
# Query execution timeout
spring:
  jpa:
    properties:
      hibernate:
        # Statement timeout (seconds)
        jdbc:
          statement_timeout: 30

# Connection timeout (already set to 15 sec)
# Query timeout (implicit in connection timeout)

# HTTP request timeout
server:
  servlet:
    session:
      timeout: 120m  # Session timeout
```

**Monitoring:**
```bash
# Check for long-running queries
psql -c "SELECT pid, query, query_start FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '30 seconds';"

# Kill slow queries if needed:
# psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query_start < now() - interval '1 minute';"
```

---

## 📈 CAPACITY PLANNING

### **Resource Requirements by Load**

| Metric | 50 Traders | 100 Traders | 200 Traders |
|--------|-----------|-----------|-----------|
| **CPU Cores** | 4 | 8 | 16 |
| **Memory** | 4GB | 8GB | 16GB |
| **DB Connections** | 60 | 80 | 100 |
| **Redis Memory** | 500MB | 1GB | 2-3GB |
| **Network (Mbps)** | 50 | 100 | 200 |
| **Disk (SSD)** | 200GB | 400GB | 800GB |

### **Load Testing Recommendations**

```bash
# Test at each capacity level
./scripts/load-test-phase1.sh --concurrent-users 50  # Current
./scripts/load-test-phase1.sh --concurrent-users 100  # Breaking point?
./scripts/load-test-phase1.sh --concurrent-users 150  # Over limit

# Expected results
50 traders:  p99 < 150ms ✓
100 traders: p99 < 200ms ✓ (this is the target)
150 traders: p99 > 300ms ✗ (need Phase 2)
```

---

## 🚨 CRITICAL THRESHOLDS

**Set up alerts for these:**

| Metric | Warning | Critical | Action |
|--------|---------|----------|--------|
| DB Connections | > 50 of 60 | > 55 of 60 | Add pool, scale DB |
| Cache Hit Rate | < 80% | < 60% | Increase TTLs |
| Order Latency p99 | > 250ms | > 300ms | Check indexes |
| Memory Usage | > 80% | > 90% | Increase heap |
| Thread Count | > 100 | > 150 | Investigate leak |
| Error Rate | > 0.5% | > 2% | Urgent debugging |

---

## ✅ OPTIMIZATION CHECKLIST

Before claiming Phase 1 is complete:

- [ ] Order creation p99 < 200ms
- [ ] Portfolio query p99 < 150ms
- [ ] Cache hit rate > 90%
- [ ] Error rate < 0.5%
- [ ] DB connections < 80% utilization
- [ ] Memory usage stable (no leaks)
- [ ] Thread count stable
- [ ] All alerts configured
- [ ] Baseline metrics captured
- [ ] Load test completed

---

## 📚 RELATED DOCUMENTATION

- `PHASE_1_TROUBLESHOOTING_GUIDE.md` - When things go wrong
- `PHASE_1_DATABASE_OPTIMIZATION.md` - Implementation details
- `monitoring/alerts.yml` - Configured alerts
- `PHASE_1_FINAL_SIGN_OFF.md` - Overall status

---

**Last Updated:** 2026-06-05  
**Status:** Comprehensive Tuning Guide Ready  

