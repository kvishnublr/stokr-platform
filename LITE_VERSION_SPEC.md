# Stokr Platform LITE - Version 7
## Simplified Multi-User Multi-Strategy Trading Platform

**Version:** 7.0-LITE  
**Based on:** Release_v4  
**Target:** Lightweight, fast, production-ready trading platform

---

## 🎯 LITE vs FULL Comparison

| Feature | Full Version | LITE Version | Priority |
|---------|--------------|--------------|----------|
| Multi-user support | ✅ Full RBAC | ✅ Basic roles | P0 |
| Multi-strategy | ✅ 100+ per user | ✅ 20 per user | P0 |
| Broker integration | 5 brokers | Zerodha only | P0 |
| Backtesting | ✅ Full engine | ✅ Basic | P1 |
| Risk management | ✅ Advanced | ✅ Basic | P0 |
| Portfolio allocation | ✅ Advanced | ✅ Simple | P0 |
| Marketplace | ✅ Full | ❌ Removed | P2 |
| Copy trading | ✅ Full | ❌ Removed | P2 |
| Admin dashboard | 10+ pages | 3 pages | P1 |
| Mobile support | ✅ | ❌ Removed | P2 |

**Target Code Reduction:** 1000+ classes → ~250 classes (-75%)

---

## 🏗️ LITE Architecture

### Module Consolidation (14 → 5)

```
CURRENT (14 modules):
├── stokr-auth
├── stokr-user
├── stokr-broker
├── stokr-strategy
├── stokr-execution
├── stokr-oms
├── stokr-risk
├── stokr-marketdata
├── stokr-backtest
├── stokr-websocket
├── stokr-admin
├── stokr-bootstrap
├── stokr-common
└── stokr-organization (new)

LITE (5 modules):
├── stokr-core          # Auth + User + Common
├── stokr-trading       # Strategy + Execution + OMS
├── stokr-broker        # Broker integration (Zerodha only)
├── stokr-risk          # Risk management
└── stokr-ui            # Frontend
```

### Simplified Directory Structure

```
stokr-platform/
├── stokr-core/
│   └── src/main/java/com/stokr/core/
│       ├── domain/           # 5 entities (User, Role, Org, Strategy, Instance)
│       ├── repository/       # 5 repositories
│       ├── service/          # 8 services
│       ├── controller/       # 6 controllers
│       └── security/         # RBAC, JWT
│
├── stokr-trading/
│   └── src/main/java/com/stokr/trading/
│       ├── domain/           # Signal, Order, Position, Trade
│       ├── strategy/         # Strategy engine (simplified)
│       ├── execution/        # Order execution
│       ├── oms/             # Order management
│       └── repository/       # Data access
│
├── stokr-broker/
│   └── src/main/java/com/stokr/broker/
│       ├── zerodha/         # Zerodha adapter
│       └── adapter/          # Broker interface
│
├── stokr-risk/
│   └── src/main/java/com/stokr/risk/
│       ├── engine/           # Risk calculation
│       └── limits/           # Position limits
│
└── stokr-ui/
    └── src/                  # React frontend
```

---

## 📊 LITE Data Model

### Core Entities Only

```sql
-- ============================================================================
-- ORGANIZATIONS (Multi-tenancy)
-- ============================================================================
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    plan_type VARCHAR(20) DEFAULT 'FREE', -- FREE, PRO
    max_users INTEGER DEFAULT 3,
    max_strategies INTEGER DEFAULT 10,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- USERS (Simplified)
-- ============================================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role VARCHAR(20) DEFAULT 'TRADER', -- ADMIN, MANAGER, TRADER
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- BROKER ACCOUNTS (Zerodha only)
-- ============================================================================
CREATE TABLE broker_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    vendor_code VARCHAR(20) DEFAULT 'ZERODHA',
    access_token_encrypted TEXT,
    api_key VARCHAR(100),
    account_number VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- STRATEGIES
-- ============================================================================
CREATE TABLE strategies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    creator_id UUID REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    code TEXT, -- Strategy code (JSON/DSL)
    parameters JSONB DEFAULT '{}',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- STRATEGY INSTANCES (Running strategies per user)
-- ============================================================================
CREATE TABLE strategy_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID REFERENCES strategies(id),
    user_id UUID REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    broker_account_id UUID REFERENCES broker_accounts(id),
    name VARCHAR(255),
    symbol VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    mode VARCHAR(20) DEFAULT 'PAPER', -- PAPER, LIVE
    allocation DECIMAL(18,2) DEFAULT 10000,
    max_position_size DECIMAL(18,2) DEFAULT 1000,
    risk_multiplier DECIMAL(5,2) DEFAULT 1.0,
    max_daily_loss DECIMAL(18,2) DEFAULT 500,
    status VARCHAR(20) DEFAULT 'STOPPED', -- RUNNING, STOPPED, PAUSED
    started_at TIMESTAMP,
    stopped_at TIMESTAMP,
    last_signal_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- SIGNALS & ORDERS
-- ============================================================================
CREATE TABLE signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    symbol VARCHAR(50) NOT NULL,
    signal_type VARCHAR(10) NOT NULL, -- ENTRY, EXIT
    side VARCHAR(10) NOT NULL, -- BUY, SELL
    confidence DECIMAL(5,2),
    entry_price DECIMAL(18,4),
    target_price DECIMAL(18,4),
    stop_loss DECIMAL(18,4),
    quantity DECIMAL(18,4),
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, EXECUTED, SKIPPED, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    executed_at TIMESTAMP
);

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    signal_id UUID REFERENCES signals(id),
    user_id UUID REFERENCES users(id),
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) DEFAULT 'MARKET',
    quantity DECIMAL(18,4),
    price DECIMAL(18,4),
    status VARCHAR(20) DEFAULT 'PENDING',
    broker_order_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW(),
    filled_at TIMESTAMP,
    cancelled_at TIMESTAMP
);

CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    user_id UUID REFERENCES users(id),
    symbol VARCHAR(50) NOT NULL,
    quantity DECIMAL(18,4),
    avg_price DECIMAL(18,4),
    current_price DECIMAL(18,4),
    pnl DECIMAL(18,2),
    status VARCHAR(20) DEFAULT 'OPEN',
    opened_at TIMESTAMP DEFAULT NOW(),
    closed_at TIMESTAMP
);

-- ============================================================================
-- PERFORMANCE TRACKING (Simplified)
-- ============================================================================
CREATE TABLE daily_performance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    date DATE NOT NULL,
    equity DECIMAL(18,2),
    returns DECIMAL(10,4),
    trades_count INTEGER DEFAULT 0,
    wins INTEGER DEFAULT 0,
    losses INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(instance_id, date)
);
```

---

## 🔧 LITE Service Architecture

### Core Services (8 instead of 217)

```java
// stokr-core services
├── AuthService           // Login, register, JWT
├── UserService          // User management
├── OrganizationService   // Org management, limits
└── RbacService          // Permission checks

// stokr-trading services
├── StrategyService      // Strategy CRUD, execution
├── SignalService        // Signal generation, processing
├── OrderService         // Order placement, management
└── PositionService      // Position tracking

// stokr-risk services
└── RiskEngine           // Position limits, daily loss

// stokr-broker services
└── ZerodhaAdapter       // Broker API adapter
```

---

## 🌐 LITE API Endpoints

```
/api/v1
├── /auth
│   ├── POST /login
│   ├── POST /register
│   ├── POST /refresh
│   └── GET  /me
│
├── /organizations
│   ├── GET  /{id}
│   ├── PUT  /{id}
│   └── GET  /{id}/usage
│
├── /users
│   ├── GET  /           # List org users (admin)
│   ├── GET  /{id}
│   ├── POST /
│   ├── PUT  /{id}
│   └── DELETE /{id}
│
├── /strategies
│   ├── GET  /
│   ├── POST /
│   ├── GET  /{id}
│   ├── PUT  /{id}
│   ├── DELETE /{id}
│   └── GET  /{id}/backtest
│
├── /instances
│   ├── GET  /
│   ├── POST /
│   ├── GET  /{id}
│   ├── PUT  /{id}
│   ├── DELETE /{id}
│   ├── POST /{id}/start
│   ├── POST /{id}/stop
│   └── GET  /{id}/signals
│
├── /orders
│   ├── GET  /
│   ├── GET  /{id}
│   └── POST /{id}/cancel
│
├── /positions
│   ├── GET  /
│   └── GET  /{symbol}
│
├── /brokers
│   ├── POST /connect
│   ├── GET  /status
│   └── POST /disconnect
│
├── /performance
│   ├── GET  /instances/{id}
│   └── GET  /instances/{id}/equity
│
└── /backtest
    ├── POST /run
    └── GET  /{id}/result
```

**Total APIs: ~40 endpoints (vs 200+ in full version)**

---

## 🎨 LITE Frontend Pages

### Only 8 Core Pages

```
pages/
├── LoginPage.tsx           # Login/Register
├── DashboardPage.tsx       # Overview (all strategies)
├── StrategiesPage.tsx      # Strategy list + create
├── InstancePage.tsx        # Single strategy detail
├── OrdersPage.tsx          # Order history
├── PositionsPage.tsx       # Current positions
├── ProfilePage.tsx         # User settings
└── AdminPage.tsx           # User management (admin only)
```

**vs 40+ pages in full version**

---

## 🚀 LITE Deployment

### Docker Compose (Single File)

```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - DB_HOST=postgres
      - REDIS_HOST=redis
    depends_on:
      - postgres
      - redis

  postgres:
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=stokr_lite
      - POSTGRES_USER=stokr
      - POSTGRES_PASSWORD=stokr
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine

  ui:
    build: ./stokr-ui
    ports:
      - "3000:80"

volumes:
  pgdata:
```

---

## 📋 LITE Implementation Tasks

### Task 1: Module Consolidation
- [ ] Create `stokr-core` from auth + user + common
- [ ] Create `stokr-trading` from strategy + execution + oms
- [ ] Keep `stokr-broker` (simplified to Zerodha only)
- [ ] Keep `stokr-risk` (simplified)
- [ ] Keep `stokr-ui` (simplified to 8 pages)

### Task 2: Entity Consolidation
- [ ] Reduce entities from 50+ to ~15
- [ ] Merge similar entities
- [ ] Remove unused fields
- [ ] Simplify relationships

### Task 3: Service Consolidation
- [ ] Reduce services from 217 to ~20
- [ ] Merge god-class services
- [ ] Remove redundant services
- [ ] Simplify business logic

### Task 4: API Consolidation
- [ ] Reduce endpoints from 200+ to ~40
- [ ] Merge similar endpoints
- [ ] Remove unused endpoints
- [ ] Simplify request/response DTOs

### Task 5: UI Consolidation
- [ ] Reduce pages from 40+ to 8
- [ ] Merge similar pages
- [ ] Remove admin/advanced pages
- [ ] Simplify components

### Task 6: Testing
- [ ] Add unit tests for core services
- [ ] Add integration tests for APIs
- [ ] Add E2E tests for critical flows

---

## 📊 Code Reduction Metrics

| Metric | Current | LITE Target | Reduction |
|--------|---------|-------------|-----------|
| Java Classes | 1,009 | ~250 | -75% |
| Service Classes | 217 | ~20 | -90% |
| Repositories | 90 | ~15 | -83% |
| DTOs | 79 | ~20 | -75% |
| API Endpoints | 200+ | ~40 | -80% |
| Frontend Pages | 40+ | 8 | -80% |
| Frontend Components | 200+ | 30 | -85% |

---

## ✅ LITE Feature Checklist

### P0 - Must Have
- [ ] User registration & login (JWT)
- [ ] Organization management (multi-tenancy)
- [ ] 3 user roles (Admin, Manager, Trader)
- [ ] Strategy creation & editing
- [ ] Strategy instance management
- [ ] Start/Stop/Pause strategies
- [ ] Signal generation (basic)
- [ ] Order placement
- [ ] Position tracking
- [ ] Basic P&L calculation
- [ ] Zerodha broker integration
- [ ] Paper trading mode
- [ ] Basic backtesting

### P1 - Should Have
- [ ] Daily performance tracking
- [ ] Equity curve
- [ ] Trade history
- [ ] Basic risk limits
- [ ] Real-time updates (WebSocket)

### P2 - Nice to Have (Not in LITE)
- [ ] Live trading mode
- [ ] Advanced analytics
- [ ] Strategy marketplace
- [ ] Copy trading
- [ ] Multiple brokers
- [ ] Advanced backtesting

---

## 🧪 LITE Test Plan

### Unit Tests
- AuthService: 10 tests
- StrategyService: 10 tests
- OrderService: 10 tests
- RiskEngine: 5 tests

### Integration Tests
- Auth APIs: 5 tests
- Strategy APIs: 10 tests
- Order APIs: 10 tests

### E2E Tests
- User flow: Register → Login → Create Strategy → Start
- Trading flow: Signal → Order → Position → P&L

---

## 📈 Success Criteria

| Metric | Target |
|--------|--------|
| Build time | < 2 minutes |
| Startup time | < 30 seconds |
| API response (p95) | < 200ms |
| Code coverage | > 60% |
| Memory usage | < 512MB |
| Docker image size | < 500MB |

---

**Document Version:** 1.0  
**Branch:** Release_v7  
**Status:** Implementation Ready
