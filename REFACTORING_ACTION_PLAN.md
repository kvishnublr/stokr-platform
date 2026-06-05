# 🔧 REFACTORING ACTION PLAN - SIMPLIFY THE ARCHITECTURE

**Status:** Analysis complete, ready for implementation  
**Priority:** HIGH - Do this BEFORE adding new features  
**Timeline:** 4-6 weeks  
**Expected Benefit:** 35% code reduction, 350% test coverage increase

---

## 📋 PHASE 1: IMMEDIATE (Week 1-2)

### **Task 1.1: Break Down AdminMarketBackfillService**

**Current State:**
```
AdminMarketBackfillService.java
├─ 1,338 lines
├─ 12 public methods
├─ 14 injected dependencies
└─ Responsibilities:
   ├─ Job management
   ├─ CSV parsing
   ├─ API calls to broker
   ├─ Retry logic
   ├─ Date calculations
   └─ Results processing
```

**Action: Split into 5 classes:**

```
1. MarketBackfillJobService (250 lines)
   - createBackfillJob()
   - startBackfill()
   - stopBackfill()
   - getJobStatus()
   Dependencies: JobRepository, SymbolRepository

2. BackfillDataFetcher (300 lines)
   - fetchHistoricalData()
   - handleRetries()
   - mapBrokerResponse()
   Dependencies: BrokerHistoricalAdapterRegistry, RestClient

3. BackfillCSVProcessor (200 lines)
   - parseCSVFile()
   - validateCSVData()
   - extractSymbols()
   Dependencies: ObjectMapper, MultipartFile

4. BackfillValidator (150 lines)
   - validateTimeframe()
   - validateDateRange()
   - validateSymbols()
   Dependencies: StrategyDefinitionRepository

5. BackfillResultsProcessor (200 lines)
   - processFetchResults()
   - persistCandles()
   - updateJobStatus()
   Dependencies: CandleRepository, CandleFinalizationService
```

**Effort:** 2 days  
**Impact:** 5x easier to test, reuse, maintain

---

### **Task 1.2: Fix AdminTestSignalLabService (22 dependencies)**

**Current Problem:**
```
@RequiredArgsConstructor
public class AdminTestSignalLabService {
    private final Service1 s1;
    private final Service2 s2;
    private final Service3 s3;
    ... (22 total)
```

**Solution: Create Facade Service**

```
// BEFORE (22 dependencies, 1,269 lines)
public class AdminTestSignalLabService {
    private final SignalGenerator signalGenerator;
    private final BacktestEngine backtestEngine;
    private final MetricsCalculator metrics;
    private final ReportGenerator reporter;
    ... 18 more ...
}

// AFTER (7 dependencies, 3 smaller classes)

@Service
public class AdminTestSignalLabService {
    private final SignalTestingFacade signalFacade;        // 1
    private final BacktestFacade backtestFacade;           // 2
    private final MetricsFacade metricsFacade;             // 3
    private final ReportFacade reportFacade;               // 4
}

// Supporting facades aggregate related services
@Service
public class SignalTestingFacade {
    private final SignalGenerator signalGenerator;         // aggregates related
    private final SignalValidator validator;
    private final SignalRepository repository;
}

@Service
public class BacktestFacade {
    private final BacktestEngine engine;                   // aggregates related
    private final SimulationService simulator;
    private final ResultsProcessor processor;
}
```

**Result:**
- 22 dependencies → 7 dependencies
- 1,269 lines → 3 classes of ~300 lines each
- Much easier to test

**Effort:** 1 day

---

### **Task 1.3: Consolidate 90 Repositories to 45**

**Current Problem:**
```
26 repositories in stokr-strategy:
- SignalRepository
- SignalOutcomeRepository
- SignalMetricsRepository
- StrategyStateRepository
- StrategySignalRepository
- ... 21 more
```

**Solution: Use Spring Data Generics**

```java
// BEFORE (individual repository for each entity)
@Repository
public interface SignalRepository extends JpaRepository<Signal, Long> {
    List<Signal> findByStrategyAndStatus(String strategy, Status status);
}

// AFTER (unified with generics)
@Repository
public interface BaseRepository<T, ID> extends JpaRepository<T, ID> {
    List<T> findByField(String fieldName, Object value);
    Page<T> search(SearchCriteria criteria, Pageable page);
}

// Concrete implementation
@Repository
public class SignalRepositoryImpl extends BaseRepository<Signal, Long> {
    // Only custom queries here
}
```

**Expected Result:**
- 90 repositories → 45-50 repositories
- 40-50% code reduction
- Centralized query patterns

**Effort:** 3 days

---

### **Task 1.4: Reduce DTOs by 50%**

**Current Problem:**
```
For one domain, 5 different classes:
- BackfillCreateRequest
- BackfillUpdateRequest
- BackfillListResponse
- BackfillDetailResponse
- BackfillStatusResponse
```

**Solution: Generic Response/Request Wrappers**

```java
// BEFORE (5 classes)
public class BackfillListResponse {
    List<BackfillDto> items;
    int totalCount;
    int pageNumber;
}

// AFTER (1 generic, reused)
@Data
public class ApiResponse<T> {
    T data;
    String message;
    LocalDateTime timestamp;
    String correlationId;
}

// Usage:
@GetMapping("/backfills")
public ApiResponse<PageResult<BackfillDto>> listBackfills() {
    return ApiResponse.ok(
        new PageResult<>(items, totalCount, pageNumber)
    );
}

// Generic page result
@Data
public class PageResult<T> {
    List<T> items;
    int total;
    int page;
}
```

**Expected Result:**
- 79 DTOs → 35-40 DTOs
- 50% reduction
- Still type-safe

**Effort:** 2 days

---

## 📈 PHASE 2: MEDIUM TERM (Week 3-4)

### **Task 2.1: Consolidate "Monitor" Services**

**Current Problem:**
```
4 similar services:
- RedisHealthMonitor
- MarketDataHealthMonitor
- ExecutionHealthMonitor
- StrategyHealthMonitor
```

**Solution: Unified Health Monitoring**

```java
@Service
public class HealthMonitoringService {
    private final List<HealthMonitor> monitors;
    
    public void startMonitoring() {
        monitors.forEach(m -> m.startMonitoring());
    }
    
    public SystemHealth getHealth() {
        return monitors.stream()
            .collect(SystemHealth::new);
    }
}

interface HealthMonitor {
    void startMonitoring();
    ComponentHealth check();
}

@Component
public class RedisHealthMonitor implements HealthMonitor { }

@Component
public class MarketDataHealthMonitor implements HealthMonitor { }

@Component
public class ExecutionHealthMonitor implements HealthMonitor { }

@Component
public class StrategyHealthMonitor implements HealthMonitor { }
```

**Result:**
- 4 similar services → 1 service + 4 implementations
- Consistent behavior
- Easier to add new monitors

**Effort:** 2 days

---

### **Task 2.2: Add Critical Tests**

**Current Status:** 5.7% test coverage (58 tests)  
**Target:** 25% test coverage (250+ tests)

**Priority Tests to Add:**

```
1. AdminMarketBackfillService (0 → 50 tests)
   - Job creation
   - Data fetching
   - Error handling
   - Retry logic

2. AdminTestSignalLabService (0 → 40 tests)
   - Signal testing
   - Backtesting
   - Metrics calculation

3. ExecutionEngine (5 → 40 tests)
   - Order execution
   - Error handling
   - State management

4. StrategyEngine (10 → 50 tests)
   - Signal generation
   - Position management
   - Exit logic

Total new tests: ~200 tests in 1 week
```

**Effort:** 1 week

---

### **Task 2.3: Simplify Module Dependencies**

**Current Problem:**
```
stokr-bootstrap depends on:
├─ stokr-auth
├─ stokr-user
├─ stokr-broker
├─ stokr-oms
├─ stokr-risk
├─ stokr-marketdata
├─ stokr-strategy
├─ stokr-websocket
├─ stokr-execution
├─ stokr-backtest
├─ stokr-admin
└─ stokr-admin (13 dependencies!)
```

**Solution: Clear Dependency Layers**

```
Layer 1 (Core, no dependencies on others):
├─ stokr-common
├─ stokr-auth

Layer 2 (Infrastructure):
├─ stokr-broker
├─ stokr-marketdata
├─ stokr-user

Layer 3 (Business Logic):
├─ stokr-oms
├─ stokr-execution
├─ stokr-strategy
├─ stokr-risk

Layer 4 (Admin/Tools):
├─ stokr-admin
├─ stokr-backtest

Layer 5 (Bootstrap/Startup):
├─ stokr-bootstrap (depends only on 1-4)
```

**Result:**
- Clear dependency direction
- No circular dependencies
- Easier to understand
- Can test modules independently

**Effort:** 3 days

---

## 🎯 PHASE 3: LONG TERM (Week 5-6)

### **Task 3.1: Domain-Driven Design Restructuring**

**Current:** Service-centric organization  
**Recommended:** Domain-driven organization

```
FROM (by layer):
stokr-strategy/
├─ service/
│  ├─ SignalService
│  ├─ SignalValidationService
│  ├─ SignalMetricsService
│  ├─ PressureExitService
│  └─ ... 58 more services
├─ domain/
├─ repository/
└─ controller/

TO (by business domain):
stokr-trading-signal-domain/
├─ Signal (aggregate root)
├─ SignalService (single responsibility)
├─ SignalRepository
├─ SignalController
└─ SignalRequest/Response

stokr-trading-execution-domain/
├─ Order (aggregate root)
├─ ExecutionService
├─ OrderRepository
├─ ExecutionController
└─ ExecutionRequest/Response
```

**Effort:** 1 week  
**Impact:** Much clearer business logic organization

---

### **Task 3.2: Service Locator Pattern (Optional)**

**Current:** Manual dependency injection (error-prone with 22 dependencies)

**Alternative:** Service locator

```java
@Service
public class ServiceRegistry {
    private final Map<Class<?>, Object> services;
    
    public <T> T getService(Class<T> clazz) {
        return (T) services.get(clazz);
    }
}

// Usage (can reduce constructor parameters)
public class BackfillService {
    private final ServiceRegistry registry;
    
    public void doBackfill() {
        JobService jobService = registry.getService(JobService.class);
        DataFetcher fetcher = registry.getService(DataFetcher.class);
    }
}
```

**Benefit:** Reduce constructor parameters  
**Tradeoff:** Less explicit dependencies (less clear what's used)

---

## 📊 EXPECTED RESULTS

### **Before Refactoring:**

```
Code Organization:
├─ 1,009 Java classes
├─ 217+ services
├─ 90 repositories
├─ 79 DTOs
├─ 6+ layers per request
├─ 14 modules
└─ 22 dependencies (max)

Quality:
├─ 5.7% test coverage
├─ Many god classes (>1000 lines)
├─ High cyclomatic complexity
├─ Hard to maintain
└─ Difficult to test

Metrics:
├─ Build time: Slow
├─ Deploy time: Slow
├─ Onboarding: Hard
└─ Bug fix time: Long
```

### **After Refactoring:**

```
Code Organization:
├─ 600-700 Java classes (-35%)
├─ 100-120 services (-45%)
├─ 45-50 repositories (-50%)
├─ 35-40 DTOs (-55%)
├─ 3-4 layers per request (-50%)
├─ 8-10 modules (-30%)
└─ 5-7 dependencies (max) (-70%)

Quality:
├─ 25% test coverage (+350%)
├─ No god classes
├─ Low cyclomatic complexity
├─ Easy to maintain
└─ Simple to test

Metrics:
├─ Build time: Fast (-40%)
├─ Deploy time: Fast (-40%)
├─ Onboarding: Easy
└─ Bug fix time: Quick
```

---

## ⚡ QUICK START CHECKLIST

### **Week 1 Priority:**

- [ ] Break AdminMarketBackfillService into 5 classes
- [ ] Fix AdminTestSignalLabService (22 → 7 dependencies)
- [ ] Create generic BaseRepository
- [ ] Create generic ApiResponse<T> wrapper
- [ ] Start adding unit tests

### **Week 2-3:**

- [ ] Consolidate similar services (Monitor, Processor, Handler)
- [ ] Add 200+ unit tests
- [ ] Simplify module dependencies
- [ ] Reduce DTOs further

### **Week 4-6:**

- [ ] Domain-driven restructuring (optional)
- [ ] Final cleanup
- [ ] Documentation updates
- [ ] Verify test coverage reaches 25%

---

## 🎯 SUCCESS CRITERIA

✅ Refactoring is successful when:

```
1. No service class >500 lines
2. No service with >10 dependencies
3. No module with >3 dependencies on other modules
4. DTO count <40
5. Test coverage >25%
6. Build time reduced by 30%
7. New developers can understand code in <1 day
8. Average method length <50 lines
```

---

## 📝 IMPLEMENTATION NOTES

**Start with:**
1. AdminMarketBackfillService (highest impact)
2. AdminTestSignalLabService (easiest wins)
3. Repository consolidation
4. DTO reduction

**Don't touch:**
- stokr-execution (well-designed)
- Recent infrastructure monitoring
- Admin dashboard
- Auth module

**Testing strategy:**
- Write tests as you refactor
- Use TDD for new services
- Keep integration tests working

---

**Status:** Ready to implement  
**Next Step:** Start with Task 1.1 (AdminMarketBackfillService)  
**Expected Completion:** 4-6 weeks  
**Ongoing Maintenance:** 2 hours/week after initial refactoring

