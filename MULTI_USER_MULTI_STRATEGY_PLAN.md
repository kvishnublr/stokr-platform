# TradeX-Style Multi-User Multi-Strategy Trading Platform
## Comprehensive Development Plan

**Date:** 2026-06-16  
**Project:** Stokr Platform Enhancement  
**Target:** TradeX-like multi-user, multi-strategy automated trading platform

---

## 📋 EXECUTIVE SUMMARY

This document provides a comprehensive plan to enhance the existing Stokr platform into a full-featured multi-user, multi-strategy trading platform similar to TradeX. The plan covers architecture, data models, API design, frontend components, security, and deployment strategies.

**Current State:** Single-user focused trading platform with basic multi-strategy support  
**Target State:** Full multi-tenant SaaS platform supporting unlimited users with multiple strategies per user

---

## 🎯 CORE FEATURES (TradeX Comparison)

| Feature | TradeX | Stokr (Current) | Stokr (Target) |
|---------|--------|-----------------|----------------|
| Multi-user support | ✅ Full | ⚠️ Basic | ✅ Full |
| Multiple strategies per user | ✅ 50+ | ⚠️ 5-10 | ✅ 100+ |
| Strategy marketplace | ✅ | ❌ | ✅ |
| Copy trading | ✅ | ❌ | ✅ |
| Portfolio allocation | ✅ | ⚠️ Basic | ✅ Advanced |
| Performance tracking | ✅ Per strategy | ⚠️ Combined | ✅ Per strategy |
| Role-based access | ✅ | ❌ | ✅ |
| API access | ✅ | ⚠️ Limited | ✅ Full |

---

## 🏗️ ARCHITECTURE OVERVIEW

### Current Architecture (Modular Monolith)
```
┌─────────────────────────────────────────────────────────┐
│                    stokr-platform                        │
├─────────────┬─────────────┬─────────────┬─────────────┤
│ stokr-auth  │ stokr-user  │ stokr-strategy│ stokr-exec │
├─────────────┼─────────────┼─────────────┼─────────────┤
│ stokr-broker│ stokr-oms   │ stokr-risk  │ stokr-market│
├─────────────┼─────────────┼─────────────┼─────────────┤
│ stokr-backtest│stokr-websocket│stokr-admin│stokr-common│
└─────────────┴─────────────┴─────────────┴─────────────┘
```

### Recommended Architecture Evolution
```
┌──────────────────────────────────────────────────────────────────┐
│                         API GATEWAY (Kong/AWS)                    │
├──────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │   AUTH API   │  │  USER API    │  │    TRADING API          │ │
│  │  (Identity)  │  │  (Profiles)  │  │  (Strategies/Instances) │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
├──────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │ BROKER API   │  │ PORTFOLIO   │  │    MARKETPLACE API      │ │
│  │  (Brokers)   │  │    API      │  │  (Strategy Sharing)     │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
├──────────────────────────────────────────────────────────────────┤
│                    EVENT BUS (RabbitMQ/Apache Kafka)              │
├─────────────┬─────────────┬─────────────┬─────────────┬─────────┤
│   Strategy  │   Order     │   Risk      │   Market    │  User   │
│   Engine    │   Manager    │   Engine    │   Data      │  Events │
├─────────────┴─────────────┴─────────────┴─────────────┴─────────┤
│                    PostgreSQL + Redis + S3                        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🗄️ DATA MODEL DESIGN

### Enhanced Multi-Tenant Data Model

```sql
-- ============================================================================
-- ORGANIZATION & BILLING
-- ============================================================================

CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    plan_type VARCHAR(50) DEFAULT 'FREE', -- FREE, PRO, ENTERPRISE
    max_users INTEGER DEFAULT 1,
    max_strategies INTEGER DEFAULT 5,
    max_brokers INTEGER DEFAULT 1,
    billing_email VARCHAR(255),
    stripe_customer_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- USER MANAGEMENT (Enhanced from current)
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role VARCHAR(50) NOT NULL DEFAULT 'TRADER', -- ADMIN, MANAGER, TRADER, VIEWER
    status VARCHAR(50) DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, PENDING
    avatar_url VARCHAR(500),
    email_verified BOOLEAN DEFAULT FALSE,
    two_factor_enabled BOOLEAN DEFAULT FALSE,
    preferences JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_name VARCHAR(50) NOT NULL,
    permissions JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, role_name)
);

CREATE TABLE user_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    key_hash VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(10) NOT NULL,
    name VARCHAR(100),
    permissions JSONB DEFAULT '[]',
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- BROKER ACCOUNTS (Multi-Broker Support)
-- ============================================================================

CREATE TABLE broker_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id),
    vendor_code VARCHAR(50) NOT NULL, -- ZERODHA, DHAN, ANGEL, FYERS
    account_number VARCHAR(100),
    account_name VARCHAR(255),
    is_primary BOOLEAN DEFAULT FALSE,
    margin_limit DECIMAL(18,2),
    risk_limit DECIMAL(18,2),
    status VARCHAR(50) DEFAULT 'ACTIVE',
    credentials_encrypted JSONB, -- Encrypted broker credentials
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- STRATEGIES
-- ============================================================================

CREATE TABLE strategy_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    creator_id UUID REFERENCES users(id),
    strategy_key VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100), -- MOMENTUM, MEAN_REVERSION, BREAKOUT, SCALPING
    tags JSONB DEFAULT '[]',
    code_source TEXT, -- Strategy code
    parameters JSONB DEFAULT '{}', -- Default parameters
    risk_rules JSONB DEFAULT '{}',
    is_public BOOLEAN DEFAULT FALSE,
    is_marketplace BOOLEAN DEFAULT FALSE,
    price DECIMAL(18,2), -- For marketplace (monthly price)
    rating DECIMAL(3,2) DEFAULT 0,
    usage_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(organization_id, strategy_key)
);

CREATE TABLE strategy_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id UUID REFERENCES strategy_definitions(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id),
    broker_account_id UUID REFERENCES broker_accounts(id),
    name VARCHAR(255),
    symbol VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    execution_mode VARCHAR(20) DEFAULT 'SIMULATED', -- SIMULATED, PAPER, LIVE, BOTH
    parameters JSONB DEFAULT '{}', -- User-overridden parameters
    allocation_amount DECIMAL(18,2),
    max_position_size DECIMAL(18,2),
    risk_multiplier DECIMAL(5,2) DEFAULT 1.0,
    max_daily_loss DECIMAL(18,2),
    max_drawdown DECIMAL(18,2),
    runtime_state VARCHAR(30) DEFAULT 'STOPPED', -- RUNNING, STOPPED, PAUSED
    orchestration_state VARCHAR(30) DEFAULT 'MANAGED', -- MANAGED, MANUAL
    started_at TIMESTAMP,
    stopped_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE strategy_signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id) ON DELETE CASCADE,
    symbol VARCHAR(50) NOT NULL,
    signal_type VARCHAR(20) NOT NULL, -- ENTRY, EXIT
    side VARCHAR(10) NOT NULL, -- BUY, SELL
    confidence DECIMAL(5,2),
    entry_price DECIMAL(18,4),
    target_price DECIMAL(18,4),
    stop_loss DECIMAL(18,4),
    quantity DECIMAL(18,4),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    processed_at TIMESTAMP,
    status VARCHAR(30) DEFAULT 'PENDING' -- PENDING, EXECUTED, SKIPPED, FAILED
);

-- ============================================================================
-- CAPITAL & ALLOCATION
-- ============================================================================

CREATE TABLE portfolio_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    total_capital DECIMAL(18,2) NOT NULL,
    available_capital DECIMAL(18,2) NOT NULL,
    reserved_capital DECIMAL(18,2) DEFAULT 0,
    currency VARCHAR(10) DEFAULT 'INR',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE allocation_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID REFERENCES portfolio_allocations(id) ON DELETE CASCADE,
    strategy_definition_id UUID REFERENCES strategy_definitions(id),
    min_allocation DECIMAL(18,2),
    max_allocation DECIMAL(18,2),
    max_positions INTEGER DEFAULT 10,
    priority INTEGER DEFAULT 1,
    auto_rebalance BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- PERFORMANCE & ANALYTICS
-- ============================================================================

CREATE TABLE strategy_performance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    equity DECIMAL(18,2),
    returns DECIMAL(10,4),
    benchmark_returns DECIMAL(10,4),
    alpha DECIMAL(10,4),
    beta DECIMAL(10,4),
    sharpe_ratio DECIMAL(8,4),
    max_drawdown DECIMAL(10,4),
    win_rate DECIMAL(5,4),
    profit_factor DECIMAL(8,4),
    trades_count INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    avg_win DECIMAL(18,2),
    avg_loss DECIMAL(18,2),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(instance_id, date)
);

CREATE TABLE leaderboard (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    strategy_instance_id UUID REFERENCES strategy_instances(id),
    period VARCHAR(20) NOT NULL, -- DAILY, WEEKLY, MONTHLY, ALL_TIME
    rank INTEGER,
    returns DECIMAL(10,4),
    sharpe_ratio DECIMAL(8,4),
    win_rate DECIMAL(5,4),
    trades_count INTEGER,
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, strategy_instance_id, period)
);

-- ============================================================================
-- MARKETPLACE
-- ============================================================================

CREATE TABLE marketplace_listings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_definition_id UUID REFERENCES strategy_definitions(id),
    seller_id UUID REFERENCES users(id),
    title VARCHAR(255),
    description TEXT,
    monthly_price DECIMAL(18,2),
    yearly_price DECIMAL(18,2),
    min_capital DECIMAL(18,2),
    required_broker VARCHAR(50),
    features JSONB DEFAULT '[]',
    screenshots JSONB DEFAULT '[]',
    reviews JSONB DEFAULT '[]',
    avg_rating DECIMAL(3,2) DEFAULT 0,
    sales_count INTEGER DEFAULT 0,
    status VARCHAR(50) DEFAULT 'DRAFT', -- DRAFT, PENDING, APPROVED, REJECTED
    published_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE marketplace_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id UUID REFERENCES marketplace_listings(id),
    buyer_user_id UUID REFERENCES users(id),
    subscription_type VARCHAR(20) DEFAULT 'MONTHLY',
    starts_at TIMESTAMP,
    expires_at TIMESTAMP,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    auto_renew BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- COPY TRADING
-- ============================================================================

CREATE TABLE copy_trading_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_user_id UUID REFERENCES users(id),
    leader_user_id UUID REFERENCES users(id),
    strategy_instance_id UUID REFERENCES strategy_instances(id),
    copy_percentage DECIMAL(5,2) DEFAULT 100, -- 100% = full copy
    max_capital_per_trade DECIMAL(18,2),
    max_total_investment DECIMAL(18,2),
    stop_loss_percentage DECIMAL(5,2),
    take_profit_percentage DECIMAL(5,2),
    auto_close_on_leader_stop BOOLEAN DEFAULT TRUE,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE copied_trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    copy_rule_id UUID REFERENCES copy_trading_rules(id),
    original_trade_id UUID,
    follower_order_id UUID,
    leader_entry_price DECIMAL(18,4),
    follower_entry_price DECIMAL(18,4),
    spread_pips DECIMAL(10,4),
    copied_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- AUDIT & COMPLIANCE
-- ============================================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50),
    resource_id UUID,
    details JSONB DEFAULT '{}',
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE trading_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL, -- STRATEGY_START, STRATEGY_STOP, ORDER_PLACED, etc.
    order_id UUID,
    symbol VARCHAR(50),
    side VARCHAR(10),
    quantity DECIMAL(18,4),
    price DECIMAL(18,4),
    result VARCHAR(50),
    error_message TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);
```

---

## 🔐 SECURITY & PERMISSIONS MODEL

### Role-Based Access Control (RBAC)

```java
// Role Hierarchy
enum UserRole {
    SUPER_ADMIN,      // Platform-wide admin
    ORG_ADMIN,        // Organization admin
    MANAGER,          // Team manager
    TRADER,           // Regular trader
    VIEWER,           // Read-only access
    API_USER          // API-only access
}

// Permission Types
enum Permission {
    // User Management
    USER_CREATE, USER_READ, USER_UPDATE, USER_DELETE,
    USER_ROLE_ASSIGN,
    
    // Strategy Management
    STRATEGY_CREATE, STRATEGY_READ, STRATEGY_UPDATE, STRATEGY_DELETE,
    STRATEGY_START, STRATEGY_STOP, STRATEGY_PAUSE,
    STRATEGY_CODE_VIEW, STRATEGY_CODE_EDIT,
    
    // Broker Management
    BROKER_CONNECT, BROKER_DISCONNECT, BROKER_READ, BROKER_UPDATE,
    
    // Portfolio
    PORTFOLIO_VIEW, PORTFOLIO_ALLOCATE, PORTFOLIO_REBALANCE,
    
    // Marketplace
    MARKETPLACE_VIEW, MARKETPLACE_PUBLISH, MARKETPLACE_SUBSCRIBE,
    
    // Copy Trading
    COPY_TRADING_ENABLE, COPY_TRADING_FOLLOW,
    
    // Admin
    AUDIT_VIEW, SYSTEM_CONFIG, BILLING_MANAGE
}

// Organization-level policies
@Service
public class OrganizationPolicyService {
    
    public boolean canCreateStrategy(Organization org, User user) {
        int currentCount = strategyDefinitionRepository.countByOrg(org.getId());
        return currentCount < org.getMaxStrategies();
    }
    
    public boolean canAddUser(Organization org) {
        int currentCount = userRepository.countByOrg(org.getId());
        return currentCount < org.getMaxUsers();
    }
    
    public boolean canUseLiveExecution(User user) {
        return org.getPlanType() != PlanType.FREE && 
               hasPermission(user, Permission.STRATEGY_START);
    }
}
```

---

## 🌐 API ARCHITECTURE

### API Structure

```
/api/v1
├── /auth
│   ├── POST /login
│   ├── POST /register
│   ├── POST /refresh
│   ├── POST /logout
│   └── POST /verify-email
│
├── /users
│   ├── GET    /me
│   ├── PUT    /me
│   ├── GET    /{userId}
│   ├── POST   / (admin)
│   ├── PUT    /{userId}
│   ├── DELETE /{userId}
│   └── GET    /{userId}/api-keys
│
├── /organizations
│   ├── GET    /{orgId}
│   ├── PUT    /{orgId}
│   ├── POST   /{orgId}/users
│   ├── GET    /{orgId}/usage
│   └── POST   /{orgId}/invite
│
├── /strategies
│   ├── GET    /definitions
│   ├── POST   /definitions
│   ├── GET    /definitions/{id}
│   ├── PUT    /definitions/{id}
│   ├── DELETE /definitions/{id}
│   ├── GET    /definitions/{id}/backtest
│   └── POST   /definitions/{id}/clone
│
├── /instances
│   ├── GET    /
│   ├── POST   /
│   ├── GET    /{id}
│   ├── PUT    /{id}
│   ├── DELETE /{id}
│   ├── POST   /{id}/start
│   ├── POST   /{id}/stop
│   ├── POST   /{id}/pause
│   ├── POST   /{id}/resume
│   └── GET    /{id}/signals
│
├── /portfolios
│   ├── GET    /
│   ├── POST   /
│   ├── GET    /{id}
│   ├── PUT    /{id}
│   ├── GET    /{id}/allocations
│   └── POST   /{id}/rebalance
│
├── /brokers
│   ├── GET    /
│   ├── POST   /connect
│   ├── GET    /{id}
│   ├── PUT    /{id}
│   ├── DELETE /{id}
│   └── POST   /{id}/disconnect
│
├── /marketplace
│   ├── GET    /strategies
│   ├── GET    /strategies/{id}
│   ├── POST   /strategies/{id}/subscribe
│   ├── GET    /my-subscriptions
│   └── POST   /strategies/{id}/review
│
├── /copy-trading
│   ├── GET    /leaders
│   ├── POST   /follow
│   ├── GET    /my-followers
│   ├── PUT    /rules/{id}
│   └── DELETE /rules/{id}
│
├── /performance
│   ├── GET    /instances/{id}/metrics
│   ├── GET    /instances/{id}/equity-curve
│   ├── GET    /instances/{id}/trades
│   └── GET    /leaderboard
│
└── /webhooks
    ├── POST   /broker/{vendor}
    └── POST   /system
```

### API Response Format

```java
// Standard API Response
@Data
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private ErrorInfo error;
    private PaginationInfo pagination;
    private long timestamp;
    
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .data(data)
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static <T> ApiResponse<T> paginated(T data, Page page) {
        return ApiResponse.<T>builder()
            .success(true)
            .data(data)
            .pagination(PaginationInfo.builder()
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build())
            .timestamp(System.currentTimeMillis())
            .build();
    }
}

// Error Response
@Data
public class ErrorInfo {
    private String code;
    private String message;
    private String details;
    private List<FieldError> fieldErrors;
}

// Pagination Info
@Data
public class PaginationInfo {
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}
```

---

## 🎨 FRONTEND ARCHITECTURE

### Recommended Component Structure

```
stokr-ui/
├── src/
│   ├── components/
│   │   ├── auth/
│   │   │   ├── LoginForm.tsx
│   │   │   ├── RegisterForm.tsx
│   │   │   └── TwoFactorModal.tsx
│   │   │
│   │   ├── layout/
│   │   │   ├── AppShell.tsx
│   │   │   ├── MultiUserSidebar.tsx
│   │   │   ├── OrgSwitcher.tsx
│   │   │   └── UserMenu.tsx
│   │   │
│   │   ├── strategies/
│   │   │   ├── StrategyCard.tsx
│   │   │   ├── StrategyEditor.tsx
│   │   │   ├── StrategyParameters.tsx
│   │   │   ├── StrategyBacktest.tsx
│   │   │   └── StrategyMonitor.tsx
│   │   │
│   │   ├── portfolio/
│   │   │   ├── AllocationChart.tsx
│   │   │   ├── AllocationTable.tsx
│   │   │   ├── RebalanceModal.tsx
│   │   │   └── CapitalOverview.tsx
│   │   │
│   │   ├── performance/
│   │   │   ├── EquityCurve.tsx
│   │   │   ├── PerformanceMetrics.tsx
│   │   │   ├── TradeHistory.tsx
│   │   │   └── LeaderboardTable.tsx
│   │   │
│   │   ├── marketplace/
│   │   │   ├── StrategyListingCard.tsx
│   │   │   ├── MarketplaceFilters.tsx
│   │   │   ├── StrategyDetails.tsx
│   │   │   └── SubscribeModal.tsx
│   │   │
│   │   └── admin/
│   │       ├── UserManagement.tsx
│   │       ├── RoleEditor.tsx
│   │       └── OrgSettings.tsx
│   │
│   ├── pages/
│   │   ├── DashboardPage.tsx          # Multi-strategy overview
│   │   ├── StrategiesPage.tsx        # Strategy list/management
│   │   ├── StrategyDetailPage.tsx   # Single strategy deep-dive
│   │   ├── PortfolioPage.tsx         # Capital allocation
│   │   ├── PerformancePage.tsx       # Analytics
│   │   ├── MarketplacePage.tsx       # Strategy marketplace
│   │   ├── CopyTradingPage.tsx       # Copy trading
│   │   ├── AdminPage.tsx             # User/org management
│   │   └── SettingsPage.tsx           # User settings
│   │
│   ├── stores/
│   │   ├── authStore.ts              # User authentication state
│   │   ├── organizationStore.ts     # Current org context
│   │   ├── strategyStore.ts          # Strategy management
│   │   ├── portfolioStore.ts         # Capital allocation
│   │   └── realtimeStore.ts          # WebSocket connections
│   │
│   ├── hooks/
│   │   ├── useUser.ts
│   │   ├── useOrganization.ts
│   │   ├── useStrategies.ts
│   │   ├── usePortfolio.ts
│   │   └── usePermission.ts         # RBAC checks
│   │
│   ├── services/
│   │   ├── api/
│   │   │   ├── client.ts
│   │   │   ├── auth.ts
│   │   │   ├── users.ts
│   │   │   ├── strategies.ts
│   │   │   ├── instances.ts
│   │   │   ├── portfolios.ts
│   │   │   ├── brokers.ts
│   │   │   ├── marketplace.ts
│   │   │   └── performance.ts
│   │   │
│   │   └── realtime/
│   │       ├── websocket.ts
│   │       ├── signals.ts
│   │       └── positions.ts
│   │
│   └── lib/
│       ├── permissions.ts            # Permission utilities
│       ├── validation.ts             # Form validation
│       └── formatting.ts             # Data formatting
```

### Key UI Components

```tsx
// Multi-User Dashboard Component
const MultiUserDashboard = () => {
  const { organization } = useOrganization();
  const { strategies, performance } = useStrategies();
  const { allocations } = usePortfolio();
  
  return (
    <div className="dashboard">
      {/* Organization Header */}
      <OrgHeader organization={organization} />
      
      {/* Quick Stats */}
      <div className="stats-grid">
        <StatCard 
          title="Total P&L" 
          value={formatCurrency(performance.totalPnL)}
          change={performance.pnlChange}
        />
        <StatCard 
          title="Active Strategies" 
          value={`${strategies.active}/100`}
          change={strategies.newThisMonth}
        />
        <StatCard 
          title="Capital Deployed" 
          value={formatCurrency(allocations.deployed)}
        />
        <StatCard 
          title="Win Rate" 
          value={formatPercent(performance.winRate)}
          change={performance.winRateChange}
        />
      </div>
      
      {/* Strategy Grid */}
      <StrategyGrid strategies={strategies.instances} />
      
      {/* Allocation Overview */}
      <AllocationChart allocations={allocations} />
    </div>
  );
};

// Permission-based Render Helper
const PermissionGate = ({ 
  permission, 
  fallback = null, 
  children 
}) => {
  const { hasPermission } = usePermission();
  return hasPermission(permission) ? children : fallback;
};

// Usage
<PermissionGate permission="STRATEGY_CREATE">
  <Button onClick={createStrategy}>New Strategy</Button>
</PermissionGate>
```

---

## 🚀 IMPLEMENTATION ROADMAP

### Phase 1: Foundation (Weeks 1-4)

| Task | Duration | Effort |
|------|----------|--------|
| Enhanced database schema migration | 1 week | High |
| Organization & user management API | 1 week | High |
| Role-based access control system | 1 week | High |
| API key management system | 1 week | Medium |
| Basic multi-user UI shell | 1 week | Medium |

**Deliverables:**
- ✅ Organization entity and CRUD
- ✅ Multi-user with roles
- ✅ RBAC middleware
- ✅ API key system
- ✅ Basic admin UI

### Phase 2: Multi-Strategy Support (Weeks 5-8)

| Task | Duration | Effort |
|------|----------|--------|
| Enhanced strategy instances API | 1 week | High |
| Multi-broker account support | 1 week | High |
| Portfolio allocation system | 1 week | High |
| Strategy performance tracking | 1 week | Medium |
| Multi-strategy dashboard UI | 1 week | High |

**Deliverables:**
- ✅ Unlimited strategy instances per user
- ✅ Multiple broker connections
- ✅ Capital allocation per strategy
- ✅ Per-strategy P&L tracking
- ✅ Strategy grid dashboard

### Phase 3: Advanced Features (Weeks 9-12)

| Task | Duration | Effort |
|------|----------|--------|
| Strategy marketplace | 2 weeks | High |
| Copy trading system | 2 weeks | High |
| Performance leaderboard | 1 week | Medium |
| Advanced analytics | 1 week | Medium |

**Deliverables:**
- ✅ Strategy marketplace
- ✅ Follow/unfollow traders
- ✅ Public leaderboard
- ✅ Advanced charts

### Phase 4: Polish & Scale (Weeks 13-16)

| Task | Duration | Effort |
|------|----------|--------|
| WebSocket real-time updates | 1 week | Medium |
| Audit logging system | 1 week | Medium |
| Billing integration | 2 weeks | High |
| Performance optimization | 1 week | Medium |
| Documentation | 1 week | Low |

**Deliverables:**
- ✅ Real-time signal/position updates
- ✅ Complete audit trail
- ✅ Subscription plans
- ✅ Optimized queries

---

## 📊 EFFORT ESTIMATION

| Component | Estimated Hours | Complexity |
|-----------|-----------------|------------|
| Database Schema | 40 | Medium |
| Auth & RBAC | 80 | High |
| User Management API | 60 | Medium |
| Organization API | 40 | Medium |
| Strategy Management | 120 | High |
| Instance Management | 100 | High |
| Portfolio/Allocation | 80 | High |
| Broker Integration | 60 | High |
| Marketplace | 100 | High |
| Copy Trading | 120 | Very High |
| Performance Analytics | 80 | Medium |
| Frontend Dashboard | 160 | High |
| Frontend Admin | 80 | Medium |
| WebSocket/Real-time | 60 | Medium |
| Documentation | 40 | Low |
| **Total** | **1,220 hours** | |

---

## 🔧 KEY TECHNICAL DECISIONS

### 1. Multi-Tenancy Approach
**Decision:** Shared database with `organization_id` filtering  
**Rationale:** Simpler operations, reasonable data isolation, cost-effective for SaaS

### 2. Real-time Communication
**Decision:** WebSocket via existing stokr-websocket module  
**Rationale:** Already implemented, supports strategy signals and position updates

### 3. Strategy Isolation
**Decision:** Each strategy instance runs in its own execution context  
**Rationale:** Prevents one strategy's errors from affecting others

### 4. Capital Management
**Decision:** Real-time capital reservation with Redis  
**Rationale:** Fast checks, eventual consistency with PostgreSQL

### 5. Permission Caching
**Decision:** JWT tokens with permission claims + Redis cache  
**Rationale:** Fast permission checks, 5-minute cache refresh

---

## 📝 MIGRATION STRATEGY

### Existing Data Migration

```sql
-- Migrate existing single-user to organization
INSERT INTO organizations (id, name, slug, plan_type, max_users, max_strategies)
SELECT 
    gen_random_uuid(),
    'Personal',
    'personal-' || id::text,
    'FREE',
    1,
    5
FROM users WHERE id = (SELECT MIN(id) FROM users);

-- Migrate existing users
UPDATE users 
SET organization_id = (SELECT id FROM organizations LIMIT 1);

-- Migrate existing strategy definitions
UPDATE strategy_definitions
SET organization_id = (SELECT id FROM organizations LIMIT 1);

-- Migrate existing strategy instances
UPDATE strategy_instances
SET organization_id = (SELECT id FROM organizations LIMIT 1);
```

---

## ✅ SUCCESS METRICS

| Metric | Target | Measurement |
|--------|--------|-------------|
| Users per organization | 50+ | Count |
| Strategies per user | 100+ | Count |
| API response time | <200ms p95 | APM |
| Strategy execution latency | <50ms | Metrics |
| Real-time update latency | <500ms | WebSocket |
| Uptime | 99.9% | Monitoring |

---

## 🚨 RISKS & MITIGATIONS

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Performance degradation with many users | Medium | High | Sharding, caching, indexing |
| Strategy isolation failures | Low | Critical | Sandboxed execution, circuit breakers |
| Data leakage between orgs | Low | Critical | Row-level security, thorough testing |
| Broker API rate limits | Medium | Medium | Rate limiting, caching |
| Complex permission bugs | Medium | Medium | RBAC testing suite |

---

## 📚 REFERENCES

- TradeX Platform: https://tradetron.tech/
- Multi-tenancy patterns: https://docs.microsoft.com/en-us/azure/azure-sql/database/designing-multi-tenant-saas-apps
- RBAC best practices: https://csrc.nist.gov/projects/role-based-access-control

---

**Document Version:** 1.0  
**Next Review:** After Phase 1 completion  
**Owner:** Stokr Platform Team
