# Simplified Algo Trading Platform (Tradetron-like)

## Current State (Release_v4)
- **1,038 Java files** across **13 microservice modules**
- Over-engineered: event sourcing, replay checkpoints, simulation harnesses, institutional diagnostics, confidence scoring engines, order flow analysis
- Complex deployment: multiple Spring Boot apps, RabbitMQ, Redis, separate UI app

## Target State
- **~120-140 Java files** in **1 Spring Boot monolith** with clean package structure
- **Simple React + Vite** frontend (Tradetron-style dashboard)
- Core features: multi-user, multi-broker, strategy catalog, deploy/stop, PnL tracking
- 3 core strategies: ORB (Opening Range Breakout), VWAP Bounce, Gap Fill
- Full admin panel with kill switch, deployment management, error logs
- Safety nets: EOD auto-square-off, duplicate prevention, position reconciliation, broker token refresh

---

## Architecture Overview

```
stokr-lite/
├── backend/                    # Single Spring Boot app
│   └── src/main/java/com/stokr/
│       ├── auth/               # JWT auth, user registration
│       ├── user/               # User profile, broker connections
│       ├── broker/             # Broker adapters (Zerodha, Dhan, Fyers)
│       ├── strategy/           # Strategy catalog + core strategies
│       ├── engine/             # Strategy execution engine (paper + live)
│       ├── oms/                # Orders, trades, positions, PnL
│       ├── risk/               # Risk rules + safety nets
│       ├── admin/              # Admin panel APIs
│       ├── marketdata/         # Market data feed abstraction
│       └── config/             # App config, security, CORS
├── frontend/                   # React + Vite SPA
│   └── src/
│       ├── pages/              # Dashboard, Strategies, Brokers, Admin, Settings
│       ├── components/         # Reusable UI components
│       ├── api/                # API client
│       └── hooks/              # Custom hooks
└── docker-compose.yml          # PostgreSQL + App
```

---

## Task 1: Create Project Skeleton

Create the Spring Boot monolith with Maven:
- Single `pom.xml` with dependencies: Spring Boot 3.x, Spring Security, Spring Data JPA, PostgreSQL, JWT (jjwt), Lombok, MapStruct
- `application.yml` with DB config, JWT secret, broker config placeholders
- Docker Compose with PostgreSQL + app service
- `.env.example` for secrets

---

## Task 2: Auth Module (`com.stokr.auth`)

Simplified from 30 files to ~8 files:

| File | Purpose |
|------|---------|
| `AuthUser.java` | JPA entity (id, email, password, role, enabled) |
| `AuthRole.java` | Enum: ADMIN, TRADER |
| `AuthRepository.java` | Spring Data repo |
| `JwtService.java` | Generate/validate JWT tokens |
| `JwtAuthFilter.java` | Once-per-request filter |
| `SecurityConfig.java` | Security filter chain, CORS, public routes |
| `AuthService.java` | Register, login, refresh token |
| `AuthController.java` | POST /auth/register, /auth/login, /auth/refresh |

---

## Task 3: User and Broker Module (`com.stokr.user`, `com.stokr.broker`)

Simplified from 72+17 = 89 files to ~15 files:

**User:**
| File | Purpose |
|------|---------|
| `UserProfile.java` | Entity (name, phone, capital, riskProfile) |
| `UserProfileRepository.java` | Repo |
| `UserProfileService.java` | CRUD profile |
| `UserProfileController.java` | GET/PUT /api/profile |

**Broker:**
| File | Purpose |
|------|---------|
| `BrokerAccount.java` | Entity (userId, brokerName, accessToken, status) |
| `BrokerAccountRepository.java` | Repo |
| `BrokerAdapter.java` | Interface: placeOrder(), getPositions(), getMargins() |
| `ZerodhaAdapter.java` | Zerodha Kite implementation |
| `DhanAdapter.java` | Dhan implementation |
| `FyersAdapter.java` | Fyers implementation |
| `BrokerRegistry.java` | Maps broker name -> adapter |
| `BrokerService.java` | Connect/disconnect broker, list connections |
| `BrokerController.java` | POST /api/brokers/connect, DELETE /api/brokers/{id} |
| `BrokerOAuthController.java` | OAuth callback handling per broker |
| `BrokerOrderRequest.java` | DTO: symbol, qty, side, orderType |
| `BrokerOrderResponse.java` | DTO: orderId, status |
| `BrokerPosition.java` | DTO: symbol, qty, avgPrice, pnl |

---

## Task 4: Strategy Catalog Module (`com.stokr.strategy`)

Simplified from 254 files to ~15 files:

| File | Purpose |
|------|---------|
| `Strategy.java` | Entity: name, description, type, params JSON, enabled, assetClass |
| `StrategyRepository.java` | Repo |
| `StrategyService.java` | CRUD strategy catalog |
| `StrategyController.java` | GET/POST/PUT /api/strategies |
| `StrategyPlugin.java` | Interface: evaluate(MarketContext) -> Signal |
| `MarketContext.java` | DTO: symbol, candles, indicators |
| `Signal.java` | DTO: symbol, side, entryPrice, stopLoss, target, confidence |
| `OrbStrategy.java` | Opening Range Breakout plugin |
| `VwapBounceStrategy.java` | VWAP Bounce plugin |
| `GapFillStrategy.java` | Gap Fill plugin |
| `StrategyParams.java` | DTO for configurable params per strategy |

Each strategy plugin is a simple class with one method: `evaluate(MarketContext) -> Signal`. No confidence scoring engines, no order flow analysis -- just clean entry logic.

---

## Task 5: Execution Engine (`com.stokr.engine`)

Simplified from 170 files to ~15 files:

| File | Purpose |
|------|---------|
| `Deployment.java` | Entity: userId, strategyId, brokerAccountId, mode(PAPER/LIVE), capital, status, createdAt |
| `DeploymentRepository.java` | Repo |
| `DeploymentService.java` | Deploy/stop strategies, list active deployments |
| `DeploymentController.java` | POST /api/deployments, DELETE /api/deployments/{id} |
| `ExecutionEngine.java` | Core loop: scan -> signal -> risk check -> place order |
| `EntryManager.java` | Handle entry signals: validate, calculate qty, place order |
| `ExitManager.java` | Handle exit signals: SL hit, target hit, EOD square-off |
| `PaperBroker.java` | Simulated broker for paper trading |
| `SignalProcessor.java` | Process signals from strategies, dedup logic |
| `SchedulerService.java` | @Scheduled tasks: market scan intervals, EOD cleanup |
| `MarketScanner.java` | Orchestrates scanning universe for each active deployment |
| `OrderRecoveryService.java` | Handle broker timeouts, partial fills, retry logic |
| `PositionReconciler.java` | On startup: reconcile open positions with broker |
| `BrokerTokenRefresher.java` | Auto-refresh broker tokens (daily for Zerodha) |

---

## Task 6: OMS Module (`com.stokr.oms`)

Simplified from 64 files to ~10 files:

| File | Purpose |
|------|---------|
| `Order.java` | Entity: deploymentId, symbol, side, qty, price, status, brokerOrderId |
| `Trade.java` | Entity: orderId, fillPrice, fillQty, fillTime |
| `Position.java` | Entity: deploymentId, symbol, qty, avgPrice, realizedPnl |
| `OrderRepository.java` | Repo |
| `TradeRepository.java` | Repo |
| `PositionRepository.java` | Repo |
| `OrderService.java` | Place/cancel orders, track fills |
| `PositionService.java` | Track positions, compute PnL |
| `PnlService.java` | Daily PnL, cumulative PnL, per-deployment PnL |
| `OmsController.java` | GET /api/orders, /api/positions, /api/pnl |

---

## Task 7: Risk and Safety Module (`com.stokr.risk`)

Simplified from 42 files to ~12 files:

**Risk Rules:**
| File | Purpose |
|------|---------|
| `RiskRule.java` | Interface: evaluate(RiskContext) -> RiskDecision |
| `RiskContext.java` | DTO: deployment, order, positions, dailyPnl |
| `RiskEngine.java` | Runs all rules, returns pass/fail |
| `MaxDailyLossRule.java` | Stop trading if daily loss > threshold |
| `MaxPositionsRule.java` | Limit open positions per deployment |
| `MaxTradeQtyRule.java` | Limit order quantity |
| `CapitalLimitRule.java` | Validate total deployed capital <= available capital |
| `CooldownRule.java` | Prevent duplicate signals within N minutes |

**Safety Nets:**
| File | Purpose |
|------|---------|
| `KillSwitchService.java` | Global kill switch: stop all trading instantly |
| `EodSquareOffService.java` | Auto-square-off all intraday positions at 3:15 PM |
| `IdempotencyService.java` | Prevent duplicate order placement (idempotency keys) |
| `ErrorLogService.java` | Capture and store execution errors for admin viewing |

---

## Task 8: Market Data Module (`com.stokr.marketdata`)

Simplified from 29 files to ~5 files:

| File | Purpose |
|------|---------|
| `MarketDataService.java` | Interface: getCandles(), getLtp(), getQuote() |
| `Candle.java` | DTO: open, high, low, close, volume, timestamp |
| `BrokerMarketDataService.java` | Fetches from connected broker's historical API |
| `MarketDataController.java` | GET /api/market/quote, /api/market/candles |
| `Universe.java` | Configurable list of symbols to scan |

---

## Task 9: Admin Module (`com.stokr.admin`)

~15 files for full admin capabilities:

**Admin APIs:**
| File | Purpose |
|------|---------|
| `AdminController.java` | Admin endpoints (requires ADMIN role) |
| `AdminUserService.java` | View all users, their deployments, PnL |
| `AdminDeploymentService.java` | View all deployments, force-stop any deployment |
| `AdminOrderService.java` | View all orders/trades across platform |
| `AdminBrokerHealthService.java` | Check broker connection status for all users |
| `AdminKillSwitchController.java` | POST /api/admin/kill-switch (enable/disable) |
| `AdminSquareOffController.java` | POST /api/admin/square-off/{deploymentId} |
| `AdminErrorLogController.java` | GET /api/admin/errors (paginated error logs) |
| `AdminStrategyToggleController.java` | PATCH /api/admin/strategies/{id}/enable |

---

## Task 10: React Frontend (Tradetron-style)

**Trader Pages:**
| Page | Purpose |
|------|---------|
| **Login/Register** | Auth screens |
| **Dashboard** | Overview: total PnL, active deployments, today's trades |
| **Strategies** | Browse strategy catalog, view details, deploy with one click |
| **My Deployments** | List active deployments, stop/start, view per-deployment PnL |
| **Brokers** | Connect/disconnect brokers, OAuth flow |
| **Orders/Trades** | Order history, trade log |
| **Positions** | Live positions, PnL |
| **Settings** | Profile, risk limits, capital allocation |

**Admin Pages (role-based):**
| Page | Purpose |
|------|---------|
| **Admin Dashboard** | Platform metrics, user count, trade volume |
| **User Management** | View all users, their deployments |
| **Deployment Control** | Force-stop deployments, manual square-off |
| **Broker Health** | Connection status grid |
| **Error Logs** | Real-time error feed |
| **Kill Switch** | Emergency stop button |

Tech stack: React 18 + Vite + TailwindCSS + React Router + TanStack Query + Axios

---

## Task 11: Database Schema

Single PostgreSQL database, ~15 tables:

```sql
-- Auth
users (id, email, password_hash, role, enabled, created_at)

-- User
user_profiles (id, user_id, name, phone, total_capital, risk_profile)

-- Broker
broker_accounts (id, user_id, broker_name, access_token, refresh_token, status, created_at)

-- Strategy
strategies (id, name, description, type, params_schema, asset_class, enabled, created_at)

-- Deployment
deployments (id, user_id, strategy_id, broker_account_id, mode, capital, status, created_at)

-- OMS
orders (id, deployment_id, symbol, side, quantity, price, order_type, status, broker_order_id, created_at)
trades (id, order_id, fill_price, fill_quantity, fill_time)
positions (id, deployment_id, symbol, quantity, avg_price, realized_pnl, updated_at)

-- PnL
daily_pnl (id, deployment_id, date, gross_pnl, net_pnl, trade_count)

-- Risk
risk_configs (id, deployment_id, max_daily_loss, max_positions, max_qty_per_trade)

-- Admin
kill_switch_state (id, is_active, activated_by, activated_at, reason)
error_logs (id, deployment_id, error_type, message, stack_trace, severity, created_at)

-- Safety
idempotency_keys (id, key_hash, order_id, created_at, expires_at)
broker_token_refresh_log (id, broker_account_id, status, refreshed_at, next_refresh_at)
```

---

## Key Simplifications vs Release_v4

| Aspect | Release_v4 | Simplified |
|--------|-----------|------------|
| Modules | 13 microservices | 1 monolith (9 packages) |
| Java files | 1,038 | ~120-140 |
| Strategy complexity | Confidence scoring, order flow, regime detection | Simple evaluate() -> Signal |
| Execution | Guards, reconciliation, event journaling, simulation | Direct order placement + recovery + reconciliation |
| OMS | Event sourcing, replay checkpoints, state machines | Simple order/trade/position entities |
| Risk | 20+ rules, kill switch, live trading gates | 5 essential rules + kill switch + safety nets |
| Safety | Event sourcing, replay checkpoints | EOD square-off, reconciliation, idempotency |
| Admin | Simulation harness, signal diagnostics | Kill switch, deployment mgmt, error logs, broker health |
| UI | Institutional terminal design | Clean Tradetron-style dashboard |
| Infra | RabbitMQ, Redis, multiple DBs | PostgreSQL only |
| Deployment | Complex multi-service orchestration | Single JAR + docker-compose |

---

## Execution Order

1. Task 1: Project skeleton (pom.xml, docker-compose, application.yml)
2. Task 2: Auth module
3. Task 3: User + Broker module
4. Task 8: Market data module (needed by strategies)
5. Task 4: Strategy catalog + 3 core strategies
6. Task 7: Risk + Safety module (needed by execution)
7. Task 6: OMS module
8. Task 5: Execution engine with Entry/Exit managers + recovery
9. Task 9: Admin module (APIs)
10. Task 10: React frontend (trader + admin pages)
11. Task 11: Integration testing and polish
