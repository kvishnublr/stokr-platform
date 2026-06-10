# RELEASE_V3: MICROSERVICES ARCHITECTURE IMPLEMENTATION GUIDE

## Overview

Release_v3 implements the recommended hybrid microservices architecture for STOKR:
- **Strategy Service** (independent) - Generates trading signals
- **Execution Service** (independent) - Places orders based on signals
- **Risk Service** (monolith for now) - Synchronous risk validation
- **Market Data Service** (monolith for now) - Price feeds and candles
- **Core Trading Monolith** - Event consumer for positions, auth, user management
- **RabbitMQ** - Inter-service communication
- **Admin Dashboard** - Microservices monitoring and debugging

---

## PHASE 1: INFRASTRUCTURE FOUNDATION ✅ COMPLETE

### What's Been Implemented

#### 1. RabbitMQ Configuration (stokr-common)
**File:** `RabbitMQConfig.java`

**Queues Created:**
- `trading.signals` - Strategy → Execution (5min TTL, max 10k messages)
- `trading.orders` - Execution → Core Trading (no TTL, max 50k messages)
- `trading.exits` - Core Trading → Execution (1min TTL, max 5k messages)
- `trading.audit` - All services → Audit Logger (permanent)

**Dead Letter Queues (DLQ):**
- Each queue has a DLQ for failed messages (7-day retention)

**Configuration Details:**
```
Exchange: trading.events (TopicExchange)
DLX: trading.dlx (DirectExchange)
Routing Keys:
  - signal.generated (for signals)
  - order.executed (for orders)
  - exit.signal (for exits)
  - audit.* (for audit logs)
```

#### 2. Event Message DTOs
**Location:** `stokr-common/src/main/java/com/stokr/common/messaging/events/`

**Events Defined:**
- `SignalGeneratedEvent` - Published by Strategy Service
  ```
  Fields: signalId, symbol, side, quantity, aiScore, executionMode, trader details
  ```

- `OrderExecutedEvent` - Published by Execution Service
  ```
  Fields: orderId, signalId, symbol, filledQuantity, filledPrice, status, timing
  ```

- `ExitSignalEvent` - Published by Core Trading
  ```
  Fields: exitSignalId, orderId, reason (STOP_LOSS_HIT, etc.), P&L details
  ```

- `AuditEvent` - Published by all services
  ```
  Fields: action, actor, entityType, details, correlationId, compliance info
  ```

#### 3. MessagePublisher Service
**File:** `MessagePublisher.java`

**Responsibilities:**
- Publish events to RabbitMQ
- Auto-assign correlation IDs for request tracing
- Error handling and logging
- Automatic UUID generation for event IDs

**Usage Example:**
```java
@Autowired
private MessagePublisher publisher;

// Publish a signal
SignalGeneratedEvent event = SignalGeneratedEvent.builder()
    .symbol("INDY")
    .side("BUY")
    .aiScore(92)
    .build();
publisher.publishSignalGenerated(event);
```

#### 4. Inter-Service HTTP Communication
**Files:**
- `ServiceClient.java` - REST client for sync calls
- `HttpClientConfig.java` - RestTemplate configuration

**Features:**
- Automatic correlation ID propagation
- Configurable timeouts (default 5s connect, 10s read)
- Error handling with service-specific exceptions
- Logging of all calls and responses

**Usage Example:**
```java
@Autowired
private ServiceClient serviceClient;

// Call Risk Service synchronously
RiskValidationRequest request = new RiskValidationRequest(...);
ResponseEntity<RiskValidationResponse> response = 
    serviceClient.post(
        "http://risk-service:8081/api/v1/risk/validate",
        request,
        RiskValidationResponse.class
    );
```

#### 5. Health Monitoring Infrastructure
**Files:**
- `ServiceHealth.java` - DTO for service status
- `QueueHealth.java` - DTO for queue status
- `HealthResponse.java` - Complete system health
- `HealthMonitoringService.java` - Service implementation
- `HealthMonitorController.java` - REST API endpoints

**API Endpoints:**
```
GET  /api/v1/admin/health                    - Overall system health
GET  /api/v1/admin/health/services           - All services
GET  /api/v1/admin/health/services/{name}    - Specific service
GET  /api/v1/admin/health/queues             - All queues
GET  /api/v1/admin/health/queues/{name}      - Specific queue
GET  /api/v1/admin/health/infrastructure     - DB, RabbitMQ, cache
```

**Response Structure:**
```json
{
  "overallStatus": "UP",
  "timestamp": "2026-06-10T09:15:30Z",
  "services": [
    {
      "serviceName": "strategy-service",
      "status": "UP",
      "instances": 1,
      "responseTimeMs": 45
    }
  ],
  "queues": [
    {
      "queueName": "trading.signals",
      "status": "HEALTHY",
      "pendingMessages": 247,
      "processingRatePerSec": 50
    }
  ],
  "infrastructure": {
    "databaseStatus": "UP",
    "rabbitmqStatus": "UP"
  },
  "alerts": []
}
```

---

## PHASE 2: ADMIN DASHBOARD UI ✅ COMPLETE

### Components Implemented

#### 1. ServiceHealthPanel
**File:** `stokr-ui/src/components/admin/ServiceHealthPanel.tsx`

**Features:**
- Real-time service status (UP/DEGRADED/DOWN)
- Visual indicators with color coding
- Response time metrics
- Infrastructure health (Database, RabbitMQ)
- Auto-refresh every 10 seconds

**Display:**
```
Service Health
├─ Strategy Service    [UP]  45ms
├─ Execution Service   [UP]  78ms (2 instances)
├─ Risk Service        [UP]  12ms
├─ Market Data Service [UP]  23ms
└─ WebSocket Service   [UP]  5ms
```

#### 2. QueueMonitoringPanel
**File:** `stokr-ui/src/components/admin/QueueMonitoringPanel.tsx`

**Features:**
- Queue depth monitoring
- Pending message count
- Consumer and processing rate
- Queue capacity progress bars
- Dead-letter queue visibility
- Backing-up alerts
- Auto-refresh every 5 seconds

**Display:**
```
Message Queues
├─ trading.signals      [HEALTHY]  247 pending  50/sec
├─ trading.orders       [HEALTHY]  12 pending   100/sec
├─ trading.exits        [HEALTHY]  3 pending    20/sec
└─ trading.audit        [HEALTHY]  5234 pending 100/sec

Dead Letter Queues
├─ trading.signals.dlq  0 messages
├─ trading.orders.dlq   0 messages
```

#### 3. SignalLifecyclePanel
**File:** `stokr-ui/src/components/admin/SignalLifecyclePanel.tsx`

**Features:**
- Search signals by ID
- Complete execution timeline
- Service-by-service breakdown
- Performance metrics per step
- Error visibility
- Bottleneck identification

**Timeline Shows:**
1. Signal Generated (Strategy Service)
2. Signal Queued (RabbitMQ)
3. Risk Check (Risk Service) - sync REST call
4. Broker Submitted (Execution Service) - Zerodha API
5. Broker Filled (Broker Response)
6. Position Updated (Core Trading)

**Display:**
```
Signal: INDY BUY 100
┌─ Generated        09:15:30.123  Strategy Service  ✓ SUCCESS
├─ Queued          09:15:30.234  RabbitMQ          ✓ SUCCESS (+111ms)
├─ Risk Check      09:15:30.289  Risk Service      ✓ SUCCESS (+55ms)
├─ Broker Submitted 09:15:30.312  Execution Service ✓ SUCCESS (+23ms)
├─ Broker Filled    09:15:30.401  Broker            ✓ SUCCESS (+89ms)
└─ Position Created 09:15:30.512  Core Trading      ✓ SUCCESS (+111ms)

Total: 278ms
```

---

## PHASE 3: STRATEGY SERVICE EXTRACTION (NEXT STEP)

### What Needs to Be Done

#### 1. Extract Strategy Service to Independent JVM
**Current State:** Strategy is a module in monolith  
**Target:** Independent service that can be deployed separately

**Steps:**
1. Create `stokr-strategy-service` directory (separate repo or sub-project)
2. Move strategy logic from `stokr-strategy` module
3. Add RabbitMQ publisher dependency
4. Create Bootstrap class to start service independently
5. Add Dockerfile and docker-compose entry

**Code Changes Needed:**
```java
// In stokr-strategy module
@Service
public class StrategyExecutionService {
    @Autowired
    private MessagePublisher publisher;

    public void publishSignal(SignalGeneratedEvent event) {
        publisher.publishSignalGenerated(event);
    }
}
```

**Configuration:**
```yaml
# application.yml for strategy service
spring:
  application:
    name: strategy-service
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
```

**Docker:**
```dockerfile
FROM openjdk:21-slim
WORKDIR /app
COPY target/stokr-strategy-service.jar app.jar
EXPOSE 8081
CMD ["java", "-jar", "app.jar"]
```

#### 2. Add Signal-to-RabbitMQ Middleware
**Location:** AutomatedAPlusScannerService

**Current Code:**
```java
// Creates trade in database directly
AutomatedAPlusTrade trade = entryService.createTradeEntry(...);
```

**New Code:**
```java
// Publish signal to RabbitMQ
SignalGeneratedEvent event = SignalGeneratedEvent.builder()
    .signalId("sig-" + UUID.randomUUID())
    .symbol(symbol)
    .side(side)
    .aiScore(aiScore)
    .quantity(config.getPositionSizeQty())
    .executionMode(config.getExecutionMode())
    .build();
publisher.publishSignalGenerated(event);

// Log to audit trail
auditLogger.log("SIGNAL_GENERATED", event);
```

#### 3. Update Core Trading to Consume Signals
**New Class:** SignalConsumer

```java
@Component
public class SignalConsumer {
    @RabbitListener(queues = RabbitMQConfig.QUEUE_SIGNALS)
    public void consumeSignal(SignalGeneratedEvent event) {
        // Create order via OrderPlacementService
        // This is a SYNC call to Risk Service, then async to Broker
    }
}
```

---

## PHASE 4: EXECUTION SERVICE EXTRACTION (NEXT STEP)

### What Needs to Be Done

#### 1. Extract Execution Service
**Current State:** Order execution in monolith  
**Target:** Independent service with RabbitMQ consumer and publisher

**Architecture:**
```
Execution Service (independent)
├─ Consumes: trading.signals queue
├─ Calls (sync): Risk Service (/api/v1/risk/validate)
├─ Calls (sync): Broker Service (/api/v1/broker/order/place)
├─ Publishes: trading.orders queue
├─ Database: Redis (for idempotency dedup cache only)
└─ Instances: 2-3 (horizontally scalable)
```

#### 2. Idempotency & Deduplication
**Problem:** RabbitMQ may redeliver signals, causing duplicate orders

**Solution:**
```java
@Service
public class ExecutionService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    public void processSignal(SignalGeneratedEvent event) {
        String dedupeKey = "exec:" + event.getSignalId();
        
        // Check if already processed
        if (redisTemplate.hasKey(dedupeKey)) {
            log.warn("Signal already processed: {}", event.getSignalId());
            return;
        }
        
        // Process order
        OrderExecutedEvent result = submitOrder(event);
        
        // Cache result for 24 hours
        redisTemplate.opsForValue().set(dedupeKey, result.getOrderId(), Duration.ofHours(24));
        
        // Publish result
        publisher.publishOrderExecuted(result);
    }
}
```

#### 3. Circuit Breaker for Broker
**Problem:** If Broker API is slow/down, orders queue up

**Solution:**
```java
@Service
public class BrokerCircuitBreaker {
    @CircuitBreaker(
        name = "broker",
        fallbackMethod = "brokerFallback"
    )
    public OrderResponse placeOrder(BrokerOrderRequest request) {
        return brokerService.placeOrder(request);
    }
    
    public OrderResponse brokerFallback(BrokerOrderRequest request, Exception e) {
        // Queue for retry with exponential backoff
        orderRetryQueue.add(request);
        throw new BrokerUnavailableException("Broker circuit breaker open");
    }
}
```

---

## PHASE 5: CORE TRADING REFACTORING (NEXT STEP)

### What Needs to Be Done

#### 1. Add RabbitMQ Consumers
**New Classes:**
- `OrderExecutedConsumer` - Consumes trading.orders
- `ExitSignalConsumer` - Consumes trading.exits

**OrderExecutedConsumer:**
```java
@Component
public class OrderExecutedConsumer {
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDERS)
    public void handleOrderExecuted(OrderExecutedEvent event) {
        // Update position state
        Position position = positionService.createFromOrder(event);
        
        // Publish position update
        publisher.publishPositionUpdated(position);
        
        // Log audit
        auditLogger.log("ORDER_EXECUTED", event);
    }
}
```

#### 2. Event-Driven Position Management
**Current:** Orders directly update positions  
**New:** Orders create events, consumers update positions

```java
// Old way (synchronous)
OrderPlacementService.place(request)
  ├─ Create OMS order
  ├─ Update position
  └─ Return

// New way (asynchronous)
OrderPlacementService.place(request)
  ├─ Create OMS order
  └─ Publish to RabbitMQ
       ↓ (async)
       OrderExecutedConsumer
       ├─ Update position
       └─ Publish PositionUpdated
```

#### 3. Position Exit Monitoring
**Current:** AutomatedAPlusExitService checks positions  
**New:** Still same, but publishes ExitSignal to RabbitMQ

```java
// In AutomatedAPlusExitService
public void monitorAndExit() {
    List<Position> openPositions = positionRepository.findOpen();
    
    for (Position pos : openPositions) {
        if (shouldExit(pos)) {
            // Publish exit signal
            ExitSignalEvent exitEvent = ExitSignalEvent.builder()
                .orderId(pos.getOrderId())
                .reason("STOP_LOSS_HIT")
                .build();
            publisher.publishExitSignal(exitEvent);
        }
    }
}
```

---

## PHASE 6: DATABASE STRATEGY (OPTIONAL IN YEAR 2)

### Current: Single PostgreSQL
```
stokr_platform
├── Strategy Service → strategy_signals table
├── Execution Service → orders table
├── Risk Service → risk_limits table
├── Core Trading → positions, portfolio tables
└── Market Data → candles, prices tables
```

### Year 2+ Option: Multi-Database (Only if bottleneck)
```
PostgreSQL Primary (execution, risk, positions)
PostgreSQL Replica (market data, read-only)
Redis (cache, session state)
```

**Decision Point:** Monitor database performance in year 1. If >10K queries/sec for market data, split then.

---

## IMPLEMENTATION TIMELINE

### Current Status (June 2026)
```
✅ Phase 1: Infrastructure Foundation (COMPLETE)
   - RabbitMQ config
   - Event DTOs
   - MessagePublisher
   - Health monitoring

✅ Phase 2: Admin Dashboard (COMPLETE)
   - ServiceHealthPanel
   - QueueMonitoringPanel
   - SignalLifecyclePanel
   - Integrated into Admin Dashboard
```

### Next Steps (June-July 2026)
```
⏳ Phase 3: Strategy Service Extraction (2-3 weeks)
   - Move to independent JVM
   - Publish signals to RabbitMQ
   - Add Dockerfile
   - Deploy alongside monolith

⏳ Phase 4: Execution Service Extraction (2-3 weeks)
   - Move to independent JVM
   - Consume signals, publish orders
   - Add deduplication
   - Add circuit breakers

⏳ Phase 5: Core Trading Refactoring (1-2 weeks)
   - Add order event consumers
   - Async position updates
   - Exit signal publishing

⏳ Phase 6: Stabilization & Testing (2 weeks)
   - Load testing
   - Failure scenario testing
   - Operational runbooks
```

### Total Effort: 8-12 weeks (2-3 months)

---

## DEPLOYMENT INSTRUCTIONS

### Docker Compose (Development)
```yaml
version: '3.9'
services:
  rabbitmq:
    image: rabbitmq:3.12-management
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      - RABBITMQ_DEFAULT_USER=guest
      - RABBITMQ_DEFAULT_PASS=guest

  postgres:
    image: postgres:15
    ports:
      - "5432:5432"
    environment:
      - POSTGRES_DB=stokr
      - POSTGRES_PASSWORD=password

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  core-trading:
    image: stokr-platform:latest
    ports:
      - "8080:8080"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/stokr
      - SPRING_RABBITMQ_HOST=rabbitmq
      - REDIS_HOST=redis

  strategy-service:
    image: stokr-strategy:latest
    ports:
      - "8081:8081"
    environment:
      - SPRING_RABBITMQ_HOST=rabbitmq
      - MARKET_DATA_URL=http://core-trading:8080

  execution-service:
    image: stokr-execution:latest
    ports:
      - "8082:8082"
    environment:
      - SPRING_RABBITMQ_HOST=rabbitmq
      - RISK_SERVICE_URL=http://core-trading:8080
      - REDIS_HOST=redis
```

### Kubernetes (Production Year 2+)
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: execution-service
spec:
  replicas: 3  # Horizontal scaling
  template:
    spec:
      containers:
      - name: execution-service
        image: stokr-execution:v1.0
        ports:
        - containerPort: 8082
        env:
        - name: SPRING_RABBITMQ_HOST
          valueFrom:
            configMapKeyRef:
              name: stokr-config
              key: rabbitmq-host
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
```

---

## MONITORING & ALERTS

### Prometheus Metrics (To Add)
```
stokr_signal_generated_total
stokr_order_executed_total
stokr_execution_latency_ms
stokr_rabbitmq_queue_depth
stokr_risk_check_duration_ms
stokr_broker_api_response_ms
```

### Alerts
```
- Service DOWN (P0 - page oncall)
- Queue backing up (>5000 messages) (P1 - create ticket)
- Broker latency >1000ms (P2 - monitor)
- Database slow queries (P2 - monitor)
```

---

## TESTING CHECKLIST

### Unit Tests
- [ ] RabbitMQ message serialization
- [ ] Event deduplication logic
- [ ] Circuit breaker behavior
- [ ] Signal-to-order transformation

### Integration Tests
- [ ] Strategy service → RabbitMQ → Execution service
- [ ] Order execution → Core Trading position update
- [ ] Risk service sync calls
- [ ] Broker API failure scenarios

### Load Tests
- [ ] 100 signals/sec throughput
- [ ] 1000 orders in queue (no lag)
- [ ] Market open spike (9:15 AM)
- [ ] RabbitMQ failover

### Failure Scenarios
- [ ] RabbitMQ outage (30 min)
- [ ] Strategy Service crash during market open
- [ ] Broker API timeout
- [ ] Database replica failure

---

## SUPPORT & DEBUGGING

### View Service Logs
```bash
# Docker
docker logs stokr-strategy-service -f

# Kubernetes
kubectl logs -n trading deployment/strategy-service -f
```

### Check Queue Depth
```bash
# RabbitMQ Management UI
http://localhost:15672
# Username: guest, Password: guest
```

### Trace Signal End-to-End
```bash
# Use Admin Dashboard → Signal Lifecycle Panel
# Or query API directly:
GET /api/v1/admin/signals/{signalId}/lifecycle
```

### Check Service Health
```bash
# Get all services
GET /api/v1/admin/health

# Get specific service
GET /api/v1/admin/health/services/strategy

# Get specific queue
GET /api/v1/admin/health/queues/trading.signals
```

---

## FAQ & TROUBLESHOOTING

### Q: "Signal generated but order not placed"
**A:** Check:
1. Execution Service is running (`GET /api/v1/admin/health/services/execution`)
2. Queue depth not too high (`GET /api/v1/admin/health/queues/trading.signals`)
3. Signal lifecycle (`GET /api/v1/admin/signals/{id}/lifecycle`)

### Q: "Duplicate orders placed"
**A:** Deduplication cache issue:
1. Check Redis is running
2. Check idempotency key generation
3. Check order.

### Q: "Broker API calls timeout"
**A:** Circuit breaker activated:
1. Check broker API health
2. Check network connectivity
3. Increase timeout (default 5s)

### Q: "Risk check never returns"
**A:** Sync REST call hanging:
1. Check Risk Service is running
2. Check network latency
3. Increase timeout (default 10s)

---

## GIT HISTORY

```
Release_v3 branch:

a49704a - Phase 2: Admin Dashboard UI for Microservices Monitoring
5bb7234 - Phase 1: Microservices Infrastructure Foundation

New commits will be:
- Phase 3: Extract Strategy Service
- Phase 4: Extract Execution Service
- Phase 5: Refactor Core Trading
```

---

## NEXT STEPS

1. **Review this guide** with the team
2. **Approve Phase 3** (Strategy Service extraction)
3. **Set timeline** (2-3 months for full migration)
4. **Prepare test environment** (local docker-compose)
5. **Define SLOs** for production deployment:
   - Signal-to-order latency: <500ms (P95)
   - Order execution availability: 99.9%
   - RabbitMQ queue depth: <1000 messages
   - Service restart time: <1 minute

---

**Status:** PHASE 1-2 COMPLETE ✅ | PHASE 3-5 PENDING ⏳ | PHASE 6 OPTIONAL 🎯

**Current Branch:** `Release_v3`  
**Start Date:** June 10, 2026  
**Est. Completion:** August 2026
