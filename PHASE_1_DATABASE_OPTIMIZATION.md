# 🔧 PHASE 1: DATABASE OPTIMIZATION - IMPLEMENTATION GUIDE

**Status:** IN PROGRESS  
**Start Date:** 2026-06-05  
**Target Duration:** 4-5 days  
**Branch:** Release_v2  

---

## 📋 PHASE 1 DELIVERABLES

- [ ] Connection Pool Tuning
- [ ] Database Indexes Applied (V101 migration)
- [ ] N+1 Queries Fixed (4+ repositories)
- [ ] Query Result Caching (@Cacheable)
- [ ] Pagination Implementation
- [ ] Database Partitioning Scripts
- [ ] Load Testing (50 concurrent users)
- [ ] Performance Baseline Report

---

## ✅ TASK CHECKLIST

### Task 1: Connection Pool & Configuration (0.5 days)
- [x] HikariCP configuration prepared (application-v2.yml)
- [ ] Verify HikariCP settings in running app
- [ ] Add connection leak detection
- [ ] Monitor pool utilization metrics
- [ ] **Target:** 60 connections, < 80% utilization

### Task 2: Database Indexes (0.5 days)
- [x] V101 migration created (15 indexes)
- [ ] Apply V101 migration on staging
- [ ] Verify EXPLAIN ANALYZE on key queries
- [ ] Monitor index usage after 24h
- [ ] **Target:** 50% reduction in query time

### Task 3: N+1 Query Analysis & Fixes (1.5 days)
- [ ] Analyze all repository methods
- [ ] Identify 5+ N+1 query patterns
- [ ] Add @EntityGraph annotations
- [ ] Add custom query methods with JOINs
- [ ] Load test each fix
- [ ] **Target:** 70% reduction in query count

### Task 4: Query Result Caching (1 day)
- [ ] Add @Cacheable annotations (10+ methods)
- [ ] Configure cache eviction policies
- [ ] Monitor cache hit rate
- [ ] Test with TTL settings
- [ ] **Target:** > 90% cache hit rate

### Task 5: Pagination Implementation (0.5 days)
- [ ] Update list endpoints to use Pageable
- [ ] Add Page<T> return types
- [ ] Implement cursor-based pagination for large datasets
- [ ] **Target:** Max 100 items per page

### Task 6: Database Partitioning (1 day)
- [ ] Create partitioning scripts for signals table
- [ ] Create partitioning scripts for orders table
- [ ] Create partitioning scripts for fills table
- [ ] Test partition pruning
- [ ] **Target:** 80% reduction for time-range queries

### Task 7: Load Testing (1.5 days)
- [ ] Setup load testing environment
- [ ] Create test script for 50 concurrent users
- [ ] Monitor key metrics (latency, CPU, memory)
- [ ] Verify all p99 latencies < 200ms
- [ ] Generate baseline report
- [ ] **Target:** Order creation < 200ms, Portfolio query < 150ms

### Task 8: Documentation & Handoff (0.5 days)
- [ ] Create Phase 1 completion summary
- [ ] Document all changes made
- [ ] Create runbook for Phase 2
- [ ] Team knowledge transfer

---

## 🎯 KEY PERFORMANCE TARGETS

| Metric | Current | Target | Status |
|--------|---------|--------|--------|
| Order Creation (p99) | ~500ms | < 200ms | TBD |
| Portfolio Query (p99) | ~300ms | < 150ms | TBD |
| Signal Generation (p99) | ~800ms | < 500ms | TBD |
| API Response (p99) | ~400ms | < 250ms | TBD |
| Cache Hit Rate | ~60% | > 90% | TBD |
| DB Connections | 20 max | 60 max | TBD |
| Query Count (50 traders) | High | -70% | TBD |

---

## 📊 DATABASE OPTIMIZATION STRATEGY

### 1. Connection Pool Tuning
```yaml
HikariCP Configuration:
  - Maximum Pool Size: 60 (from 20)
  - Minimum Idle: 15 (from 10)
  - Connection Timeout: 15s
  - Idle Timeout: 10 min
  - Max Lifetime: 30 min
  - Prepared Statement Caching: 500
  - Leak Detection Threshold: 1 min
```

### 2. Index Strategy (15 Indexes)
- **Strategy/Signal:** 4 indexes for fast lookups
- **Order/User:** 4 indexes for state tracking
- **Position:** 2 indexes for symbol/strategy lookups
- **Execution:** 2 indexes for fill tracking
- **Broker:** 1 index for session management
- **Composite:** 2 indexes for common patterns
- **Time-Series:** 1 BRIN index for market data
- **Function:** 1 index for case-insensitive email

### 3. N+1 Query Fixes
Common patterns to fix:
- User → Roles (lazy-loaded, causes N+1)
- Strategy → Signals (paginated, needs @EntityGraph)
- Order → Fills (grouped aggregates)
- Position → History (time-range queries)

### 4. Caching Strategy
```yaml
Cache Configuration:
  - Strategy Configurations: 30 min TTL
  - User Profiles: 30 min TTL
  - Session Data: 120 min TTL (Redis)
  - Market Data: 1 min TTL (latest)
  - Broker Status: 5 min TTL
  - Portfolio Data: 5 min TTL
```

### 5. Pagination
- Default page size: 20 items
- Maximum page size: 100 items
- Cursor-based for large datasets
- Sorted by created_at DESC for timeline queries

### 6. Partitioning
```sql
Tables to Partition:
  1. strategy_signal (by month, 12 partitions)
  2. oms_order (by month, 12 partitions)
  3. execution_fill (by month, 12 partitions)
  4. market_data_candle (by month, 12 partitions)
```

---

## 🚀 IMPLEMENTATION STEPS

### Step 1: Pre-Implementation Verification
```bash
# Check current database statistics
psql -c "SELECT schemaname, tablename, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC;"

# Check current slow queries
psql -c "SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;"

# Create baseline metrics
./scripts/capture-baseline-metrics.sh
```

### Step 2: Apply V101 Migration
```bash
# On v2-staging environment:
./mvnw -DskipTests clean package
# Migration runs automatically on startup

# Verify indexes created:
SELECT schemaname, tablename, indexname FROM pg_indexes WHERE indexname LIKE 'idx_%' ORDER BY indexname;

# Analyze tables
ANALYZE;
```

### Step 3: Code Changes
- [ ] Update service classes with @EntityGraph
- [ ] Add @Cacheable annotations
- [ ] Add Pageable parameters to repository methods
- [ ] Create custom query methods for complex queries

### Step 4: Load Testing
```bash
# 50 concurrent traders, 60 minute test
./scripts/load-test.sh --concurrent 50 --duration 60m --scenario "realistic-trader-flow"

# Monitor during test
./scripts/monitor-metrics.sh --duration 60m
```

### Step 5: Analysis & Tuning
- Review load test results
- Identify remaining bottlenecks
- Adjust cache TTLs based on hit rates
- Fine-tune connection pool settings

---

## 📈 SUCCESS CRITERIA

**Phase 1 Complete When:**
✅ All 15 indexes created and verified  
✅ 5+ N+1 queries fixed with @EntityGraph  
✅ 10+ methods have @Cacheable caching  
✅ Pagination implemented on list endpoints  
✅ Cache hit rate > 90%  
✅ Load test: 50 concurrent, p99 < 200ms  
✅ All performance targets met (see table above)  
✅ Documentation complete  

---

## 🔗 RELATED DOCUMENTS

- `RELEASE_V2_IMPLEMENTATION_PLAN.md` - Overall roadmap
- `application-v2.yml` - Spring Boot configuration
- `stokr-bootstrap/src/main/resources/db/migration/V101__Release_V2_Optimization_Indexes.sql` - Database indexes
- `V2_DEPLOYMENT_RUNBOOK.md` - Deployment procedures

---

## 📞 NEXT PHASE

Once Phase 1 is complete:
- Proceed to Phase 2 (Caching Layer - 4-5 days)
- Setup Redis cluster with Sentinel
- Implement distributed caching
- Configure rate limiting

---

**Last Updated:** 2026-06-05  
**Status:** READY FOR IMPLEMENTATION ✅

