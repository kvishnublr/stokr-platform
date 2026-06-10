# ARCHITECTURE DECISION RECORD (ADR)

## Decision: Hybrid Microservices Over Monolith or Full 8-Service Decomposition

**Date:** June 10, 2026  
**Status:** ACCEPTED ✅  
**Affected Systems:** All trading services  
**Decision Maker:** Principal Architect + Platform Lead

---

## Context

STOKR platform was hitting a critical bottleneck: **every strategy change requires 10-15 minute downtime** because strategy logic is tightly coupled to order execution, risk validation, and position management in a monolith.

Three architectural approaches were evaluated:

1. **Keep Modular Monolith** (improve boundaries, no extraction)
2. **Full Microservices** (extract 8-10 independent services)
3. **Hybrid Microservices** (extract Strategy + Execution, keep Risk/Market Data/Auth in monolith)

---

## Decision

**Implement Hybrid Microservices Architecture:**
- Extract **Strategy Service** (independent JVM, RabbitMQ publisher)
- Extract **Execution Service** (independent JVM, RabbitMQ consumer/publisher)
- Keep **Risk Service** in monolith (synchronous validation)
- Keep **Market Data Service** in monolith (no scaling pain yet)
- Keep **Auth/User** in monolith (simple, no iteration cycles)
- Use **RabbitMQ** for asynchronous signal/order flow
- Use **REST** for synchronous risk checks
- Use **PostgreSQL** single database (schemas per service)
- Use **Docker Compose** for orchestration (not Kubernetes yet)

---

## Evaluation Summary

| Criterion | Monolith | Full 8-Service | Hybrid ✅ |
|-----------|----------|---|---|
| **Strategy deploy time** | 15 min downtime | 1 min zero downtime | 1 min zero downtime |
| **Solves stated problem** | ❌ Partially | ✅ Yes | ✅ Yes |
| **Operational complexity** | Low ⭐⭐ | High ⭐⭐⭐⭐⭐ | Medium ⭐⭐⭐ |
| **Engineering effort** | N/A | 11 weeks | 8 weeks |
| **Risk of introducing bugs** | Low | Medium | Low-Medium |
| **Scales to 5K traders** | ❌ Risky at 1K+ orders/sec | ✅ Yes | ✅ Yes (with 2-3 phases) |
| **Can handle failures** | Poor | Good | Good |
| **Team capacity** | 1-2 engineers | 4+ engineers | 2-3 engineers |
| **Ready for capital** | 5/10 | 8/10 | 8.5/10 |

---

## Why NOT Keep Monolith?

**Rejected because:**
- Still have 10-15 min downtime for strategy changes
- No path forward for future scaling
- Root cause is coupling, not just deployment
- Monolith was fine for v1; no longer sufficient for v2

---

## Why NOT Full 8-Service Model?

**Rejected because:**

### 1. Over-Engineered for Current Scale
- Current load: 20-50 orders/minute = 0.33-0.83 orders/sec
- RabbitMQ throughput: 50K msgs/sec (easily handles current + 100x growth)
- Single PostgreSQL: Handles 10K queries/sec (plenty of headroom)
- **Why extract when no bottleneck exists?** Paying complexity tax for problems you don't have

### 2. Distributed Transactions Nightmare
- Extracting Risk Service: How do you validate orders across services?
  - Async risk = orders might fill at wrong exposure levels (DANGEROUS)
  - Sync risk = defeats purpose of async architecture
- Extracting Broker Service: Increases latency between order decision and submission
  - Network call adds 10-50ms latency
  - Tight stop-losses (< 100ms) won't work reliably

### 3. Order Deduplication Complexity
- RabbitMQ can redeliver messages (network timeout, service crash)
- With 8 services, deduplication must happen in multiple places
- Redis dedup cache adds operational overhead
- Higher risk of duplicate orders in failure scenarios

### 4. Data Consistency Headaches
- 8 separate databases = eventual consistency everywhere
- Saga pattern is hard to implement and debug
- Event sourcing adds 2-3x complexity
- No immediate benefit (single DB scales fine today)

### 5. Operational Overhead
| Task | Monolith | Full 8-Service | Cost |
|------|----------|---|---|
| **Deployment** | 1 container | 8 containers | 8x more coordin |
| **Monitoring** | 1 dashboard | 8 dashboards | 8x alerts |
| **Log aggregation** | 1 log stream | 8 log streams | 8x log volume |
| **Debugging** | 1 source | 8 sources | 8x harder |
| **Rollback** | 1 rollback | Multiple service rollbacks | 8x complexity |

### 6. STOKR Doesn't Have the Pain Points 8-Services Solves
- ❌ Thousands of concurrent users needing independent scaling
- ❌ Multiple teams (1 team works here)
- ❌ Requirement for building AI strategy marketplace (maybe year 3)
- ❌ Multiple brokers simultaneously (single Zerodha)
- ❌ Geographic distribution (single data center)

---

## Why Hybrid Model?

**Selected because:**

### 1. Solves Actual Pain Point (Strategy Deployment)
```
Before: Change A+ strategy → Full rebuild (5 min) + full deploy (10 min) = 15 min downtime
After:  Change A+ strategy → Rebuild strategy service (1 min) + deploy (1 min) = 1 min zero downtime
ROI:    14-minute improvement per change × 5 changes/week × 50 weeks/year = 5,600 hours saved/year
```

### 2. Keeps What Works
- **Risk in monolith:** Risk checks MUST be synchronous
  - You can't approve an order without knowing if it violates risk limits
  - Async risk validation is dangerous in trading
  - Risk calculations are fast (<10ms per order)
  
- **Auth/User in monolith:** No iteration cycles here
  - User passwords don't change often
  - Not a deployment bottleneck
  - Extraction doesn't help

- **Market Data in monolith:** No scaling pain (for 12 months)
  - 2 years of historical data = ~50GB (single DB easy)
  - Not a bottleneck (if strategy service is separate)
  - Extract when data table hits 500GB (not before)

### 3. Safe Execution Path
```
Strategy Service (async RabbitMQ)
    ↓ (durable queue, fire-and-forget)
Execution Service (async RabbitMQ consumer)
    ├─ Risk Check (sync REST, <10ms)
    ├─ Broker Submit (sync REST, <100ms)
    └─ Publish Order Executed (async RabbitMQ)
        ↓ (durable queue)
Core Trading (async RabbitMQ consumer)
    ├─ Update position
    ├─ Check for exits
    └─ Publish exit signals
```

**Why this is safe:**
- Risk validation is still synchronous (can't bypass)
- Broker execution is synchronous (orders reliably placed)
- Async flow only for non-critical updates (position state)
- No distributed transactions
- Clear failure modes

### 4. Phased Migration Path
```
Month 1-2: Extract Strategy Service
├─ Solves stated pain (strategy deployment)
├─ Risk is: LOW (signals are fire-and-forget)
└─ Value: IMMEDIATE (1 min deploys)

Month 2-3: Extract Execution Service  
├─ Solves: Independent scaling of order volume
├─ Risk is: MEDIUM (order deduplication must work)
└─ Value: Resilience (execution independent of strategy)

Month 4-6: Monitor, iterate, learn
├─ Measure actual bottlenecks
├─ Extract next service only if evidence exists
└─ No premature optimization

Year 2: Extract remaining services based on evidence
├─ Market Data Service (only if DB >500GB)
├─ Broker Service (only if supporting 2+ brokers)
├─ WebSocket Service (only if 500+ concurrent traders)
└─ Risk Service (only if real-time rules needed)
```

### 5. Lower Risk Profile
| Risk | Full 8-Service | Hybrid | Mitigation |
|------|---|---|---|
| **Distributed transaction failure** | HIGH | LOW | Hybrid keeps sync boundaries clear |
| **Duplicate orders** | MEDIUM | LOW | Single dedup service (Execution) |
| **Signal loss** | LOW | LOW | RabbitMQ durable queues |
| **Operational complexity** | HIGH | MEDIUM | Monolith handles 70% of logic |
| **Team capacity** | 4+ engineers | 2-3 engineers | We only have 2-3 available |

---

## Metrics: Why This Works

### For Strategy Deployment
```
Current (monolith):
  Strategy change → Full build (14 modules) → Full test → Full deploy → 15 min downtime
  
With hybrid:
  Strategy change → Build strategy service → Unit test → Deploy service → 1 min zero downtime
  
Improvement: 1400% faster, zero downtime
```

### For Execution Reliability
```
With hybrid (Strategy + Execution separate):
- Strategy service down? Execution still processes queued signals ✓
- Execution service down? Strategy generates signals (they queue) ✓
- Broker down? Orders retry automatically ✓
- Database down? Position state rolls back, orders stay queued ✓
```

### For Operational Capacity
```
Monolith: 1 container to manage
Hybrid: 3 containers to manage (core + strategy + execution)
  Cost: +20% operational overhead
  Benefit: 10x faster strategy iteration

Full 8-service: 10 containers to manage
  Cost: +800% operational overhead
  Benefit: Doesn't solve our current problems
```

---

## What We Reject From Both Approaches

### From Pure Monolith Approach
- ❌ Accept 15-minute downtime for strategy changes
- ❌ Accept inability to scale strategy independently
- ❌ Accept tight coupling as "just how it is"

### From Full Microservices Approach
- ❌ Extract services with no current bottleneck
- ❌ Build distributed system before proving need
- ❌ Accept async risk validation (too risky for capital)
- ❌ Implement saga patterns (too complex for team size)
- ❌ Deploy 8 services when 2-3 solve the problem
- ❌ Require 4+ engineers when we have 2-3 available

---

## Success Criteria

### Must Have (Non-Negotiable)
- ✅ Strategy changes deploy in <2 minutes with zero downtime
- ✅ No duplicate orders even if RabbitMQ redelivers
- ✅ Risk validation still synchronous and fast (<10ms)
- ✅ Can scale Strategy and Execution services independently

### Should Have (High Priority)
- ✅ Execution service is resilient to strategy service crashes
- ✅ Broker failures don't cause order loss
- ✅ Admin dashboard shows service health and queue depth
- ✅ Can debug signal-to-order pipeline via timeline tracking

### Nice to Have (Lower Priority)
- Observable performance improvements (P95 latency)
- Multi-deployment automation
- Full event sourcing
- Kubernetes ready

---

## Risks & Mitigation

### Risk 1: RabbitMQ becomes bottleneck
**Probability:** LOW  
**Impact:** MEDIUM  
**Mitigation:**
- RabbitMQ can handle 50K msgs/sec; we'll send <500/sec in year 1
- Plan for Kafka only if we hit 1000+ orders/sec (year 2+)

### Risk 2: Order deduplication fails, causing duplicates
**Probability:** LOW  
**Impact:** CRITICAL  
**Mitigation:**
- Redis dedup cache with 24-hour TTL
- Idempotency keys on all orders
- Broker-side dedup (Zerodha checks for dupe order IDs)
- Extensive testing of failure scenarios

### Risk 3: Sync Risk Service becomes bottleneck
**Probability:** VERY LOW  
**Impact:** MEDIUM  
**Mitigation:**
- Risk checks are O(n) where n = open positions
- Current: n < 50, takes <10ms per check
- Threshold to extract: If n > 100 and <20ms (doesn't happen for 18+ months)

### Risk 4: Team can't manage 3 services
**Probability:** LOW (team says feasible)  
**Impact:** MEDIUM  
**Mitigation:**
- Start with 1 additional service (Strategy)
- Learn operational patterns
- Add Execution service only after comfortable with Strategy ops
- Use Docker Compose health checks + automated restarts

---

## Decision Alternatives Considered

### Alternative 1: Keep Monolith, Optimize Build Pipeline
**Rejected because:**
- Doesn't solve core problem (tight coupling)
- Compile time will still be 5 minutes
- Database locks will cause coordination overhead
- Monolith eventually becomes scaling limit

### Alternative 2: Extract Only Strategy Service
**Partially accepted - this is our Phase 1**

### Alternative 3: Use Kafka Instead of RabbitMQ  
**Rejected for now because:**
- RabbitMQ is simpler, sufficient for current volume
- Kafka has higher operational overhead
- Move to Kafka only if ordering or replay needed (not yet)

### Alternative 4: Use gRPC Instead of RabbitMQ
**Rejected because:**
- gRPC requires bidirectional coupling (tightly bound)
- RabbitMQ decouples services (loosely bound)
- gRPC for sync calls (REST fine), RabbitMQ for async

### Alternative 5: Implement Full Event Sourcing  
**Rejected for now because:**
- Event sourcing adds 2-3x complexity
- Only needed if regulatory audit trail required
- Can add in year 2 if needed

---

## Timeline & Effort

| Phase | Task | Effort | Timeline | Risk |
|-------|------|--------|----------|------|
| **1** | Infrastructure (RabbitMQ, events, health monitoring) | 2 weeks | Week 1-2 | LOW |
| **2** | Admin Dashboard UI | 1 week | Week 3 | LOW |
| **3** | Extract Strategy Service | 2 weeks | Week 4-5 | LOW |
| **4** | Extract Execution Service | 2 weeks | Week 6-7 | MEDIUM |
| **5** | Core Trading refactoring | 1 week | Week 8 | LOW |
| **6** | Load testing & hardening | 2 weeks | Week 9-10 | MEDIUM |
| **Total** | | 10 weeks | 2.5 months | LOW-MEDIUM |

---

## Implementation Status

✅ **COMPLETE (Phase 1-2)**
- RabbitMQ configuration with durable queues
- Event message DTOs
- MessagePublisher service
- Health monitoring infrastructure
- Admin Dashboard UI (ServiceHealth, QueueMonitoring, SignalLifecycle)

⏳ **PENDING (Phase 3-5)**
- Strategy Service extraction
- Execution Service extraction
- Core Trading refactoring

---

## Approval & Sign-Off

- **Architecture Decision:** Approved ✅
- **Engineering Lead:** Approved ✅
- **Product Lead:** Approved ✅
- **Operations Lead:** Pending (will review in Phase 3)

---

## References

- [Microservices Architecture Analysis](MICROSERVICES_ANALYSIS_REPORT.md)
- [Microservices Summary](MICROSERVICES_SUMMARY.md)
- [Release_v3 Implementation Guide](RELEASE_V3_IMPLEMENTATION_GUIDE.md)
- [Architectural Principles for Trading Systems](ARCHITECTURE_REVIEW.md)

---

## Questions & Clarifications

**Q: Why not just optimize the monolith deployment?**  
A: Root cause isn't deployment speed—it's coupling. Even with optimized build pipeline, 15 full modules recompile on any change. Extraction is the only real fix.

**Q: Won't RabbitMQ become a bottleneck?**  
A: No. RabbitMQ handles 50K msgs/sec; we'll send <500/sec for 12 months. Move to Kafka only if/when we hit 1000+ orders/sec (probably year 2).

**Q: What if we need async risk validation later?**  
A: Keep risk synchronous now. If it becomes bottleneck (unlikely), extract then. Async risk is dangerous for capital—don't do it unless you have to.

**Q: Can one engineer manage all services?**  
A: After stabilization, yes (with automated health checks). During migration, need 2-3 for testing and rollout.

---

**Decision Approved:** June 10, 2026  
**Last Updated:** June 10, 2026  
**Next Review:** After Phase 3 completion (August 2026)
