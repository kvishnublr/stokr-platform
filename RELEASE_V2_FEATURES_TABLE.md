# Release_v2 - API Endpoints & UI Features Map

**Date:** 2026-06-06  
**Backend:** Release_v2 (Current)  
**Frontend:** React/Vite (stokr-ui)  
**Deployment:** new.stokr.in

---

## Quick Overview

| Category | Controllers | Endpoints | UI Components |
|----------|-------------|-----------|---------------|
| **Trading** | 8 | 45+ | Strategy Dashboard, Terminal, Signals |
| **Market Data** | 2 | 15+ | Market Scanner, Price Feeds |
| **Orders & Positions** | 4 | 30+ | Order Book, Position Monitor |
| **User Management** | 5 | 25+ | Profile, Broker Sync, Alerts |
| **Admin & Monitoring** | 30+ | 150+ | Admin Dashboard, Operations |
| **Backtesting** | 3 | 20+ | Backtest Engine, Journal |
| **Total** | 50+ | 280+ | Complete Trading Platform |

---

## Detailed Feature Breakdown

### 🎯 **1. TRADING & STRATEGIES** (8 Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **StrategyInstanceController** | `/api/strategies/instances` | Start/Stop/Pause strategies | Active Strategies List, Controls |
| **StrategySubscriptionController** | `/api/strategies/subscribe` | Subscribe/Unsubscribe | Strategy Watchlist, Notifications |
| **StrategyRuntimeObservabilityController** | `/api/strategies/{id}/runtime` | Monitor strategy execution | Performance Metrics, Live Stats |
| **StrategyValidationController** | `/api/strategies/validate` | Validate strategies | Validation Reports, Error Logs |
| **StrategyExecutionConfigController** | `/api/strategies/config` | Configure execution params | Settings, Risk Controls |
| **ADVCashController** | `/api/strategies/adv-cash` | ADV Cash strategy data | ADV Dashboard, Screens |
| **FuturesSignalController** | `/api/signals/futures` | Futures trading signals | Signal Monitor, Charts |
| **IndexHuntController** | `/api/strategies/index-hunt` | Index hunting strategy | Index Scan Results, Heat Maps |

**UI Sections:**
- 📊 Strategy Monitor (Live instances, status, P&L)
- 📈 ADV Intelligence Dashboard (Real-time analysis)
- 🎯 Signal Generator & Monitor (View active signals)
- 📉 Backtest Results (Historical performance)

---

### 📊 **2. ORDERS & EXECUTION** (4 Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **OrderExecutionController** | `/api/orders` | Place/Modify/Cancel orders | Order Entry Form, Order Book |
| **OmsApiController** | `/api/oms` | OMS operations | Live Orders, Fills, Rejections |
| **PortfolioApiController** | `/api/portfolio` | Portfolio management | Positions, Holdings, Cash |
| **EmergencyExitController** | `/api/execution/emergency-exit` | Emergency stop trading | Emergency Button, Exit Log |

**UI Sections:**
- 📦 Order Book (All live orders with statuses)
- 💼 Position Monitor (Real-time positions, P&L)
- 💰 Cash & Margin (Available capital, utilization)
- 🚨 Emergency Controls (Kill switches, exits)

---

### 📈 **3. MARKET DATA & FEEDS** (2 Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **MarketDataController** | `/api/market-data` | Real-time quotes, OHLC | Watchlist, Price Tickers |
| **MarketLegacyController** | `/api/market/legacy` | Historical data, backtesting | Historical Charts, Analytics |

**UI Sections:**
- 📱 Watchlist (Symbol prices, bid-ask, volume)
- 📊 Market Scanner (Top movers, gainers, losers)
- 📈 Charts (1m, 5m, 15m, 1h, daily candles)
- 🔔 Price Alerts (Set alerts on symbols)

---

### 👤 **4. USER & ACCOUNT** (5 Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **TraderSelfController** | `/api/trader/self` | Current user profile | Account Info, Statistics |
| **UserProfileController** | `/api/users/profile` | User settings, preferences | Settings, Profile Edit |
| **TraderBrokerController** | `/api/trader/broker` | Broker account details | Broker Status, Margin, Risk |
| **TraderZerodhaController** | `/api/trader/zerodha` | Zerodha integration | API Keys, Token, Instruments |
| **TraderContactController** | `/api/trader/contact` | Notifications, alerts | Email, SMS, Telegram settings |

**UI Sections:**
- 👤 Profile Dashboard (User info, performance metrics)
- 🏦 Broker Connection (Zerodha OAuth, sync status)
- ⚙️ Account Settings (Email, phone, notifications)
- 📱 Alerts Setup (Price, execution, risk alerts)

---

### 📚 **5. BACKTESTING & RESEARCH** (3 Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **BacktestController** | `/api/backtest` | Run backtest simulations | Backtest Runner, Parameters |
| **BacktestJournalController** | `/api/backtest/journal` | Trade journal, analysis | Trade Journal, Statistics |
| **StrategyResearchController** | `/api/research` | Strategy research tools | Analysis Tools, Reports |

**UI Sections:**
- 🧪 Backtest Engine (Run, configure, optimize)
- 📊 Results Analysis (Equity curve, drawdown, metrics)
- 📝 Trade Journal (All trades, P&L, reasons)
- 📈 Research Dashboard (Strategy development tools)

---

### 🛡️ **6. RISK & SAFETY** (5+ Admin Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **AdminRiskDashboardController** | `/api/admin/risk` | Risk metrics, limits | Risk Dashboard, Heatmaps |
| **AdminExecutionGuardController** | `/api/admin/execution-guard` | Execution safety checks | Safety Status, Guards |
| **AdminOmsSafetyController** | `/api/admin/oms-safety` | OMS safety monitoring | Safety Metrics, Incidents |
| **AdminReconciliationController** | `/api/admin/reconciliation` | Trade reconciliation | Recon Status, Mismatches |
| **AdminCapitalController** | `/api/admin/capital` | Capital & margin management | Capital Utilization, Metrics |

**UI Sections:**
- 🛡️ Risk Monitor (Exposure, limits, VaR, Greeks)
- 🔐 Safety Dashboard (Execution guards, circuit breakers)
- 📊 Reconciliation (Broker vs System variance)
- 💳 Capital Management (Margin utilization, available funds)

---

### 📊 **7. MONITORING & DIAGNOSTICS** (10+ Admin Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **AdminOperationsSnapshotController** | `/api/admin/operations-snapshot` | Real-time system state | System Status, Components |
| **AdminOperationsStreamController** | `/api/admin/operations-stream` | Live event stream | Event Log, Real-time Feed |
| **AdminReadinessController** | `/api/admin/readiness` | System readiness checks | Health Checks, Readiness |
| **AdminBrokerInfrastructureController** | `/api/admin/broker-infra` | Broker connection status | Feed Status, Connectivity |
| **AdminOperationalDiagnosticsController** | `/api/admin/diagnostics` | System diagnostics | Diagnostics, Metrics |
| **AdminRuntimeBindingController** | `/api/admin/runtime-binding` | Runtime configuration | Config Status, Bindings |
| **AdminLogStreamController** | `/api/admin/logs` | System logs streaming | Log Viewer, Search |
| **AdminMarketSimulationController** | `/api/admin/market-sim` | Market simulation tools | Simulation Engine |
| **AdminMarketBackfillController** | `/api/admin/market-backfill` | Data backfill tools | Backfill Status, Jobs |

**UI Sections:**
- 📊 Operations Dashboard (System health, uptime)
- 📡 Feed Monitor (Market data feed status)
- ⚙️ System Status (Services, databases, queues)
- 📋 Logs & Diagnostics (Real-time logs, debugging)

---

### 🎯 **8. SIGNALS & AUTOMATION** (5+ Admin Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **AdminSignalController** | `/api/admin/signals` | Signal management | Signal Monitor, Stats |
| **AdminSignalDiagnosticsController** | `/api/admin/signal-diagnostics` | Signal debugging | Signal Flow, Latency |
| **AdminTestSignalLabController** | `/api/admin/test-signals` | Test signal generation | Signal Tester, Lab |
| **SignalExecutionDashboardController** | `/api/trader/signal-execution` | Trader signal view | Signal Dashboard, Alerts |

**UI Sections:**
- 🔔 Signal Monitor (All signals, fill rates, execution)
- 📊 Signal Analytics (Performance by strategy, symbol)
- 🧪 Signal Test Lab (Generate, test, validate signals)

---

### 💼 **9. STRATEGY DETAILS** (5+ Controllers)

| Controller | Key Endpoints | Features | UI Presentation |
|-----------|--------------|----------|-----------------|
| **StrategyEffectivenessController** | `/api/admin/strategy-effectiveness` | Strategy performance | Strategy Performance, Metrics |
| **SetupDetectionController** | `/api/strategies/setup-detection` | Setup pattern detection | Pattern Scanner, Alerts |
| **PaperTradingController** | `/api/trader/paper-trading` | Paper trading simulation | Paper Trade Account, Stats |
| **ConfidenceStrategyController** | `/api/strategies/confidence` | Confidence scoring | Confidence Metrics, Dashboard |
| **StrategyValidationController** | `/api/admin/validation` | Strategy validation | Validation Reports, Errors |

**UI Sections:**
- 📊 Strategy Performance (Win rate, drawdown, Sharpe ratio)
- 🎓 Paper Trading (Virtual account, learning)
- 🎯 Setup Detection (Pattern recognition, alerts)

---

### 🔧 **10. ADDITIONAL SYSTEMS** (10+ Admin Controllers)

| Feature | Endpoints | Purpose | UI Presentation |
|---------|-----------|---------|-----------------|
| **Auth** | `/api/auth/login`, `/api/auth/logout` | User authentication | Login Form, Sessions |
| **Backfill** | `/api/admin/backfill/*` | Data backfill operations | Backfill Jobs, Progress |
| **Broker Ops** | `/api/admin/broker-ops/*` | Broker API monitoring | API Metrics, Throttling |
| **Trade Recon** | `/api/admin/trade-recon/*` | Trade reconciliation | Recon Reports, Audits |
| **Universe Mgmt** | `/api/admin/universes/*` | Symbol universe management | Universe Editor, Scanner |
| **Notifications** | `/api/trader/notifications` | Alert notifications | Notification Center, Settings |
| **Telegram** | `/api/trader/telegram/*` | Telegram notifications | Bot Settings, Commands |
| **WhatsApp** | `/api/trader/whatsapp/*` | WhatsApp notifications | Bot Settings, Messages |

---

## UI Dashboard Sections Possible

### 👤 **Trader Dashboard**
1. **Portfolio Overview** - Cash, positions, margin, P&L
2. **Active Strategies** - Live instances, performance, signals
3. **Order Book** - Open orders, fills, execution status
4. **Watchlist** - Market data, price alerts, tickers
5. **Signal Monitor** - Active signals, fill rates, actions
6. **Performance Stats** - Win rate, Sharpe, drawdown, returns
7. **Account Settings** - Broker sync, notifications, alerts
8. **Trade Journal** - All trades, analytics, notes

### 🛡️ **Risk & Compliance**
1. **Risk Dashboard** - Exposure, VaR, Greeks, limits
2. **Safety Monitor** - Circuit breakers, execution guards
3. **Margin Monitor** - Utilization, requirements, warnings
4. **Trade Reconciliation** - Broker vs system, variances
5. **Audit Trail** - All actions, login history
6. **Compliance Reports** - Risk reports, position reports

### 📊 **Admin Dashboard**
1. **System Status** - Services, databases, queues, uptime
2. **Market Feeds** - NSE/BSE/MCX status, latency, ticks
3. **Operations** - Real-time events, stream, snapshots
4. **Diagnostics** - Logs, metrics, debugging tools
5. **Strategy Mgmt** - Deploy, configure, monitor all strategies
6. **Risk Controls** - Kill switch, pauses, emergency exits
7. **Signal Management** - Monitor, test, validate signals
8. **Data Backfill** - Jobs, progress, status

### 🧪 **Research & Testing**
1. **Backtest Engine** - Run simulations, optimize parameters
2. **Strategy Research** - Tools, analysis, development
3. **Paper Trading** - Virtual account, learning
4. **Signal Lab** - Test, validate, generate signals

---

## Data Flow & Real-Time Features

### WebSocket/Streaming Endpoints Available:
- `/ws/market-data` - Real-time quotes
- `/ws/signals` - Real-time signal feed
- `/ws/operations` - Real-time operations stream
- `/ws/orders` - Order updates stream

### Authentication:
- `/api/auth/login` - Basic auth, returns JWT
- JWT used in Authorization header for all requests

### Frontend Integration Points:
1. **Axios** - HTTP requests to `/api` endpoints
2. **SockJS + STOMP** - WebSocket for real-time updates
3. **TanStack Query** - Data fetching & caching
4. **Zustand** - State management
5. **React Router** - Navigation between dashboard sections

---

## Recommended UI Layout

```
┌─────────────────────────────────────────────────────┐
│         STOKR TRADING PLATFORM (Release_v2)        │
├────────────┬──────────────────────────────────────┤
│  SIDEBAR   │                                      │
│ ┌────────┐ │          MAIN DASHBOARD              │
│ │ Profile│ │  ┌──────────────────────────────────┐│
│ │────────│ │  │ Portfolio Overview               ││
│ │Dashbrd │ │  ├──────────────────────────────────┤│
│ │Strats  │ │  │ Active Strategies | Market Data  ││
│ │Orders  │ │  ├──────────────────────────────────┤│
│ │Watch   │ │  │ Order Book | Watchlist           ││
│ │Signals │ │  ├──────────────────────────────────┤│
│ │Risk    │ │  │ Signal Monitor | Risk Dashboard  ││
│ │Backtest│ │  ├──────────────────────────────────┤│
│ │Journal │ │  │ Trade Journal | Performance Stats││
│ │Alerts  │ │  └──────────────────────────────────┘│
│ │Admin   │ │                                      │
│ └────────┘ │                                      │
└────────────┴──────────────────────────────────────┘
```

---

## Summary: What Can Be Shown in UI

### ✅ **Implemented & Ready**
- ✅ Real-time market data (prices, charts, tickers)
- ✅ Strategy instance management (start/stop/pause)
- ✅ Order management (place, modify, cancel, view)
- ✅ Portfolio & positions tracking
- ✅ Risk monitoring (limits, exposure, margin)
- ✅ Signal monitoring & execution
- ✅ User authentication & profile
- ✅ Backtest engine & results
- ✅ Trade journal & analytics
- ✅ System monitoring (health, feeds, diagnostics)

### 🔄 **Real-Time Ready**
- Real-time quotes via WebSocket
- Live order updates
- Signal execution feed
- Operations event stream
- System health monitoring

### 🔐 **Security Features**
- User authentication (Auth Controller)
- Role-based access (admin vs trader)
- Audit trails (all actions logged)
- Emergency exit controls
- OMS safety checks

---

## Deployment Command (Release_v2)

```bash
# Build UI
cd stokr-ui
npm ci
npm run build

# Docker build & push
docker build -t new.stokr.in/stokr-ui:v2 .
docker push new.stokr.in/stokr-ui:v2

# Deploy to new.stokr.in
# Endpoints auto-configured in .env.local to point to new.stokr.in backend
```

---

**Status: Release_v2 has 50+ controllers with 280+ endpoints covering the complete trading platform!** ✅
