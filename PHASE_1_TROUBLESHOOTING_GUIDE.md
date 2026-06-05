# 🔧 PHASE 1 TROUBLESHOOTING GUIDE
## Release_v2 Database Optimization - Common Issues & Solutions

**Last Updated:** 2026-06-05  
**For:** Operations, DevOps, Support Teams  

---

## 📋 QUICK DIAGNOSIS CHECKLIST

When performance is degraded, check in this order:

```bash
# 1. Check cache status (health endpoint)
curl http://localhost:8080/actuator/health/cacheHealth | jq '.'

# 2. Check database connectivity
curl http://localhost:8080/actuator/health/db | jq '.'

# 3. Check connection pool
curl http://localhost:8080/actuator/metrics/hikaricp.connections | jq '.'

# 4. Check error rate
curl http://localhost:8080/actuator/metrics/http.server.requests | jq '.measurements[] | select(.statistic=="COUNT")'

# 5. Check Redis connectivity
redis-cli ping  # Should return PONG

# 6. Check PostgreSQL connectivity
psql -h localhost -U postgres -c "SELECT 1;"
```

---

## 🚨 COMMON ISSUES & SOLUTIONS

### **ISSUE 1: Cache Hit Rate Low (< 70%)**

**Symptoms:**
- Portfolio queries still slow (~150-200ms)
- Cache health shows "UP" but not helping

**Diagnosis:**
```bash
# Check Redis memory usage
redis-cli INFO memory
# Look for: used_memory_human, maxmemory_human

# Check if eviction is happening
redis-cli CONFIG GET maxmemory-policy
# Should be: allkeys-lru (or similar)

# Check cache sizes
redis-cli DBSIZE  # Total keys
redis-cli INFO keyspace
```

**Solutions:**

1. **Cache TTLs too short**
   ```yaml
   # In application-v2.yml, increase TTLs:
   spring:
     cache:
       redis:
         time-to-live: 3600000  # 60 min instead of 30 min
   ```

2. **Redis memory too small**
   ```bash
   # Check current max memory
   redis-cli CONFIG GET maxmemory
   
   # If < 2GB and evictions happening:
   # Increase Docker/VM Redis memory allocation
   ```

3. **Cache not being used properly**
   ```bash
   # Check @Cacheable annotations are present
   grep -r "@Cacheable" stokr-bootstrap/src/main/java/
   
   # Verify cache names match configuration
   grep -r "value =" stokr-bootstrap/src/main/java/ | grep "@Cacheable"
   ```

**Prevention:**
- Monitor cache hit rate continuously
- Alert if hit rate drops below 80%
- Review which methods are cached vs not

---

### **ISSUE 2: Order Creation Latency High (> 250ms)**

**Symptoms:**
- Order creation taking longer than 200ms p99
- Database queries slow
- Error: "slow query detected"

**Diagnosis:**
```bash
# Check if indexes exist
psql -c "SELECT COUNT(*) FROM pg_indexes WHERE indexname LIKE 'idx_oms_order%';"
# Should return: 3+

# Check slow query log
psql -c "SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;"

# Check database connection pool
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements[0].value'
# If > 50 of 60: pool exhaustion

# Check query execution plan
psql -c "EXPLAIN ANALYZE SELECT * FROM oms_orders WHERE user_id = 'xxx' AND state = 'FILLED' LIMIT 10;"
# Should use indexes (look for: "Index Scan" or "Bitmap Index Scan")
```

**Solutions:**

1. **Indexes not created**
   ```bash
   # Apply V101 migration if not done
   SELECT schemaname, tablename, indexname FROM pg_indexes 
   WHERE indexname LIKE 'idx_oms_order%' OR indexname LIKE 'idx_strategy%';
   
   # If missing, run migration:
   ./mvnw clean package -DskipTests
   # Then restart app (migrations run automatically)
   ```

2. **Query not using indexes**
   ```bash
   # Check query plan
   EXPLAIN (ANALYZE, BUFFERS) 
   SELECT * FROM oms_orders 
   WHERE user_id = 'xxx' AND state = 'FILLED' 
   ORDER BY created_at DESC LIMIT 20;
   
   # If not using index: Check filter conditions
   # Make sure WHERE clause matches index columns
   ```

3. **Database statistics stale**
   ```bash
   # Update query optimizer statistics
   ANALYZE oms_orders;
   ANALYZE strategy_signal;
   ANALYZE trader_position;
   ```

4. **Connection pool exhausted**
   ```yaml
   # In application-v2.yml, increase pool:
   spring:
     datasource:
       hikari:
         maximum-pool-size: 80  # from 60
         minimum-idle: 20       # from 15
   ```

**Prevention:**
- Monitor p99 latency continuously
- Alert if order creation > 250ms
- Weekly: ANALYZE tables
- Monthly: Check slow query log

---

### **ISSUE 3: Portfolio Query Returns Stale Data**

**Symptoms:**
- Portfolio shows old position prices
- Portfolio doesn't update after orders filled
- Cache invalidation not working

**Diagnosis:**
```bash
# Check cache invalidation is being called
grep -r "invalidatePositionCache\|@CacheEvict" stokr-bootstrap/src/main/java/

# Check if position was actually updated
SELECT user_id, symbol, quantity, updated_at FROM portfolio_positions 
WHERE user_id = 'xxx' ORDER BY updated_at DESC LIMIT 1;

# Check cache manually
redis-cli GET "position_summary::USER_ID::xxx"
```

**Solutions:**

1. **Cache not being invalidated**
   ```java
   // Make sure to call invalidation when position changes:
   positionRepository.save(position);
   cachedPortfolioSummaryService.invalidatePositionCache(userId);
   ```

2. **Wrong cache key being used**
   ```bash
   # Check actual cache keys in Redis
   redis-cli KEYS "position_summary*"
   
   # Verify key format matches @Cacheable key parameter
   ```

3. **TTL too long**
   - Reduce position_summary TTL from 10 min to 5 min:
   ```yaml
   spring:
     cache:
       redis:
         time-to-live: 300000  # 5 min
   ```

**Prevention:**
- Always call cache invalidation after writes
- Use @CacheEvict on update methods
- Test cache invalidation in pre-deployment

---

### **ISSUE 4: Redis Connection Pool Exhaustion**

**Symptoms:**
- Error: "Cannot get a resource, pool error: exhausted"
- Redis slowness
- All requests hanging

**Diagnosis:**
```bash
# Check Redis connection count
redis-cli INFO clients
# Look for: connected_clients, blocked_clients

# Check connection pool health
curl http://localhost:8080/actuator/health/cacheHealth | jq '.details'

# Check active connections in app
curl http://localhost:8080/actuator/metrics/redis.connected-clients | jq '.'
```

**Solutions:**

1. **Increase connection pool**
   ```yaml
   spring:
     data:
       redis:
         jedis:
           pool:
             max-active: 100    # from 50
             max-idle: 50       # from 25
             min-idle: 20       # from 10
   ```

2. **Redis memory full (connections blocked)**
   ```bash
   # Check Redis memory
   redis-cli INFO memory
   
   # If full:
   redis-cli FLUSHDB  # Clear cache (production warning!)
   # Better: Increase Redis memory allocation
   ```

3. **Slow Redis commands holding connections**
   ```bash
   # Check slow log
   redis-cli SLOWLOG GET 10
   
   # If many slow commands: increase timeout
   spring:
     data:
       redis:
         timeout: 5000  # 5s timeout
   ```

**Prevention:**
- Monitor redis.connected-clients < max-active
- Alert if > 80% of pool used
- Regular cache cleanup policy

---

### **ISSUE 5: Database Replication Lag High (> 1 second)**

**Symptoms:**
- Stale reads from read replicas
- "replication_lag_high" alert triggered
- Performance inconsistent

**Diagnosis:**
```bash
# Check replication lag on replica
SELECT extract(epoch FROM now() - pg_last_xact_replay_timestamp()) as replication_lag_seconds;

# Check replication status on primary
SELECT * FROM pg_stat_replication;

# Check LSN lag
SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0');
```

**Solutions:**

1. **Network latency between primary and replica**
   - Check network: `ping replica-ip`
   - Increase max_wal_senders: `max_wal_senders = 10`
   - Increase wal_keep_segments

2. **Replica falling behind (too slow)**
   - Check replica server load: `top`, `iostat`
   - Increase replica resources
   - Reduce primary write load if possible

3. **WAL archiving bottleneck**
   ```bash
   # Check WAL files
   ls -la $PGDATA/pg_wal/ | wc -l
   
   # If many files: replication is backing up
   ```

**Prevention:**
- Monitor replication_lag continuously
- Alert if > 1 second
- Use dedicated fast network for replication
- Test disaster recovery monthly

---

### **ISSUE 6: Out of Memory (OOM) Error**

**Symptoms:**
- "OutOfMemoryError: Java heap space"
- Application crashes
- Large query results

**Diagnosis:**
```bash
# Check memory usage
curl http://localhost:8080/actuator/metrics/jvm.memory.used | jq '.measurements'

# Check garbage collection
curl http://localhost:8080/actuator/metrics/jvm.gc.pause | jq '.'

# Get heap dump on error:
# JVM args: -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp
```

**Solutions:**

1. **Unbounded query results (should be fixed with pagination)**
   ```bash
   # Check for queries without LIMIT/OFFSET
   grep -r "findAll\|List<" stokr-bootstrap/src/main/java/repository/ | grep -v "Pageable"
   
   # Convert to pagination:
   Page<Entity> findAll(Pageable pageable);
   ```

2. **Increase heap memory**
   ```bash
   # JVM args
   -Xms2g -Xmx4g  # min 2GB, max 4GB (adjust for instance size)
   ```

3. **Memory leak in cache**
   ```bash
   # Check cache size
   redis-cli DBSIZE
   
   # If growing unbounded: check TTL settings
   # Verify @Cacheable has unless parameter
   ```

**Prevention:**
- Monitor jvm.memory.used < 80% of max
- Alert if memory usage trending up
- Weekly heap dump analysis
- Code review for memory leaks

---

## 🔄 RECOVERY PROCEDURES

### **Recovery Checklist**
```
1. [ ] Identify root cause (see diagnosis above)
2. [ ] Apply fix (see solutions above)
3. [ ] Restart application
4. [ ] Verify health endpoints return UP
5. [ ] Check performance metrics returning to normal
6. [ ] Run verification script: ./scripts/verify-phase1-targets.sh
7. [ ] Monitor for 1 hour
8. [ ] Document incident
```

### **When to Rollback**
```
Rollback to Phase 0 if:
- Order creation p99 > 300ms (targets can't be met)
- Error rate > 1% (system unstable)
- Database unavailable > 5 min
- Redis unavailable > 10 min (fallback working but degraded)

Rollback procedure:
./scripts/switch-traffic.sh --percentage 0 --target green
./scripts/start-blue.sh
./scripts/switch-traffic.sh --percentage 100 --target blue
```

---

## 📞 ESCALATION PATH

| Issue | Severity | First Try | Escalate To | Time Limit |
|-------|----------|-----------|-------------|-----------|
| Cache hit rate low | LOW | Check TTLs | Cache Architect | 30 min |
| Order latency high | MEDIUM | Check indexes | Database DBA | 15 min |
| OOM error | HIGH | Increase heap | DevOps Lead | 5 min |
| Redis down | CRITICAL | Check connectivity | Redis Admin | 2 min |
| DB replication lag | MEDIUM | Check network | Network Team | 10 min |

---

## 📚 RELATED DOCUMENTATION

- `PHASE_1_FINAL_SIGN_OFF.md` - Overall Phase 1 status
- `PHASE_1_DATABASE_OPTIMIZATION.md` - Implementation details
- `monitoring/alerts.yml` - All configured alerts
- `V2_DEPLOYMENT_RUNBOOK.md` - Deployment procedures

---

**Last Updated:** 2026-06-05  
**Status:** Ready for Operations  

