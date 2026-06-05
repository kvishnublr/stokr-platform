# STOKR PLATFORM - LIVE FEATURES (Release_v1)

## 📋 13 CORE MODULES

### 1. STRATEGY (235 files) - SIGNAL GENERATION ENGINE
- Signal generation from strategies (NSE/MCX/CDS)
- Strategy definitions with parameters
- Universe management (symbol groups)
- Confidence scoring + probability
- Technical indicators (RSI, VWAP, ATR)
- Daily signal caps
- Quality gates
- Historical backtesting & replay
- Test signal lab
- Manual exit suppression
- Outcome tracking (P&L, excursion, win rate)

### 2. EXECUTION (142 files) - ORDER EXECUTION
- Order placement (BUY/SELL, MARKET/LIMIT/STOP)
- Order state machine
- Position sizing
- Risk limits
- Emergency exit system
- **Target profit monitoring (15 sec intervals)**
- Stop loss enforcement
- Market close exits
- Position reconciliation
- Broker position sync

### 3. OMS (64 files) - ORDER MANAGEMENT SYSTEM
- Order tracking & state machine
- Trade execution records
- Portfolio position management
- Daily P&L snapshots
- Equity curve tracking
- Exposure calculation
- Event sourcing journal
- Replay capability

### 4. ADMIN (122 files) - ADMINISTRATION
- User management (list, status, password reset, live trading approval)
- Signal administration (list, search, stats, cleanup, benchmark)
- Signal pipeline tracing
- Infrastructure health dashboard
- System diagnostics
- Position reconciliation

### 5. USER (69 files) - USER MANAGEMENT
- Registration + email verification
- Login + JWT + refresh tokens
- Profile management
- Broker account linking (Zerodha OAuth)
- Execution mode (LIVE/SIMULATED)
- Risk profile settings
- Subscription plans
- Account status tracking

### 6. BROKER (17 files) - BROKER INTEGRATION
- **Zerodha Kite API (Primary)**
- Angel Broking (historical data)
- Dhan (historical data)
- Fyers (historical data)
- Upstox (historical data)
- Order placement + position queries
- Account margin tracking

### 7. BOOTSTRAP (83 files) - STARTUP & CORE
- Database migrations (Flyway)
- Dev mode (H2 in-memory)
- Health checks
- Admin dashboard controller
- Trader terminal views
- Position orphan detection
- Signal pipeline recovery

### 8. BACKTEST (57 files) - HISTORICAL TESTING
- Backtest execution
- Historical candle data
- Trade simulation
- P&L calculation
- Performance metrics

### 9. RISK (41 files) - RISK MANAGEMENT
- Max open positions rule
- Max portfolio drawdown rule
- Strategy max positions
- Risk limit validation
- Position sizing rules

### 10. AUTH (30 files) - AUTHENTICATION
- Registration + email verification
- Password reset flow
- JWT + refresh tokens
- RBAC (ADMIN, TRADER, USER)
- Login policy enforcement

### 11. MARKETDATA (29 files) - MARKET DATA
- Candle data ingestion
- Price + volume updates
- Feed staleness detection
- Market status tracking
- Intraday market hours

### 12. COMMON (55 files) - SHARED UTILITIES
- API response wrappers
- Exception handling
- Correlation ID tracking
- Pagination utilities

### 13. WEBSOCKET (7 files) - REAL-TIME
- WebSocket streaming
- Position updates
- Order updates
- Market data streaming

---

## 🌐 LIVE API ENDPOINTS (70+ endpoints)

### Authentication (8 endpoints)
- Register, Login, Refresh, Forgot Password, Reset Password
- Email Verification, Resend Verification, Logout

### User Profile (3 endpoints)
- Onboarding summary, Execution mode (get/update)

### Broker Integration (6 endpoints)
- Accounts, Status, Disconnect, Test Connection, Test Order, Cancel Order

### Portfolio (4 endpoints)
- Overview, Equity Curve, Exposure, Dashboard

### Signal Admin (9 endpoints)
- List, Search, Detail, Pipeline Trace, Stats, Cleanup, Benchmark, Replay, Track Outcomes

### User Admin (5 endpoints)
- List, Detail, Status, Password Reset, Live Trading Approval

### Infrastructure Diagnostics (7 endpoints)
- Health, Redis, DB, Broker, Feed, OMS, Signal

### System (additional)
- Position reconciliation, Signal diagnostics

---

## ✅ LIVE FEATURES CHECKLIST

### TRADING EXECUTION
- [x] Live trading (real orders to Zerodha)
- [x] Simulated trading (paper trades)
- [x] Execution mode switching per user
- [x] Strategy activation (go LIVE)
- [x] Order placement from signals
- [x] Position entry tracking
- [x] 7 exit types implemented
  - [x] Stop Loss (HARD_STOP)
  - [x] Target Profit (TARGET)
  - [x] Pressure-based exit (PRESSURE_EXIT)
  - [x] Time-based exit (TIME_EXIT)
  - [x] Feed protection exit
  - [x] Liquidity protection exit
  - [x] Manual exit suppression
- [x] Manual exit from broker
- [x] Position reconciliation

### SIGNAL GENERATION
- [x] NSE equity signals
- [x] MCX futures signals
- [x] CDS currency signals
- [x] Confidence scoring
- [x] Quality gates
- [x] Daily signal caps
- [x] Manual exclusion lists
- [x] Signal outcome tracking
- [x] P&L tracking per signal
- [x] Max favorable/adverse excursion

### PORTFOLIO MANAGEMENT
- [x] Real-time position tracking
- [x] P&L calculation (realized + unrealized)
- [x] Equity curve (historical snapshots)
- [x] Symbol exposure analysis
- [x] Broker exposure tracking
- [x] Daily P&L summaries
- [x] Margin utilization
- [x] Position count tracking

### USER MANAGEMENT
- [x] Registration + email verification
- [x] Login + JWT
- [x] Profile management
- [x] Broker OAuth (Zerodha)
- [x] Account status management
- [x] Risk profile selection
- [x] Subscription plan management
- [x] Execution mode preference (LIVE/SIMULATED)

### ADMIN CAPABILITIES
- [x] User management
- [x] Signal management
- [x] Strategy administration
- [x] Test signal lab
- [x] Infrastructure health monitoring
- [x] System diagnostics
- [x] Position reconciliation
- [x] Live trading approval

### DATA INTEGRITY
- [x] Event sourcing (journal)
- [x] Replay capability
- [x] Position reconciliation
- [x] Signal pipeline recovery
- [x] Orphan position detection
- [x] Correlation ID tracking

### SECURITY
- [x] JWT authentication
- [x] Email verification
- [x] Password reset flow
- [x] Role-based access control
- [x] Live trading approval requirement
- [x] Account status enforcement

---

## 🎨 UI/DASHBOARDS

- Admin Dashboard V2 (modern, animated, responsive)
- Infrastructure Health Dashboard
- Signal Management Dashboard
- Trader Terminal (positions)
- Portfolio Dashboard
- Strategy Management UI

---

## ⚡ WHAT'S WORKING WELL

✓ Core trading execution (live + simulated)
✓ Multi-exit system (7 types)
✓ Target profit monitoring (every 15 sec logs)
✓ User authentication + broker integration
✓ Portfolio tracking + equity curves
✓ Admin diagnostics + health checks
✓ Signal outcome tracking
✓ Position reconciliation
✓ Event sourcing with replay

---

## 🚨 KNOWN GAPS (Minor)

❌ PortfolioPosition missing signal_id linkage
❌ No broker health in portfolio view
❌ Execution mode not enforced at signal level
❌ Limited trader-facing UI (mostly admin-centric)

---

## 💾 DATABASE ENTITIES (90+ tables)

- strategy_signals (signals)
- oms_orders (orders)
- oms_trades (trades)
- portfolio_positions (user positions)
- portfolio_daily_summary (daily P&L)
- portfolio_pnl_snapshots (equity curve)
- auth_users (users)
- user_profiles (profiles)
- broker_accounts (broker connections)
- strategy_definitions (strategy metadata)
- strategy_instances (running strategies)
- strategy_execution_config (execution settings)
- And 80+ more...

