# Release_v2 - Quick Feature Reference

## 📊 Brief Feature Table (What's Available)

| **Feature** | **Module** | **Endpoints** | **UI Component** | **Status** |
|-----------|-----------|--------------|-----------------|-----------|
| **Trading Strategies** | strategy/ | /api/strategies/* | Strategy Monitor, Dashboard | ✅ Ready |
| **Order Management** | oms/ | /api/orders, /api/oms/* | Order Book, Entry Form | ✅ Ready |
| **Portfolio & Positions** | oms/ | /api/portfolio/* | Position Monitor, Holdings | ✅ Ready |
| **Market Data** | market-data/ | /api/market-data/* | Watchlist, Charts, Tickers | ✅ Ready |
| **Signal Generation** | strategy/ | /api/signals/* | Signal Monitor, Alerts | ✅ Ready |
| **User Profile** | user/ | /api/trader/self, /api/users/* | Profile, Settings, Account | ✅ Ready |
| **Broker Connection** | user/broker/ | /api/trader/broker/* | Zerodha Sync, OAuth | ✅ Ready |
| **Backtesting** | backtest/ | /api/backtest/* | Backtest Engine, Results | ✅ Ready |
| **Trade Journal** | backtest/ | /api/backtest/journal/* | Journal, Analytics, Stats | ✅ Ready |
| **Risk Management** | admin/ | /api/admin/risk/* | Risk Dashboard, Metrics | ✅ Ready |
| **System Monitoring** | admin/ | /api/admin/operations-* | System Status, Health | ✅ Ready |
| **Market Feeds** | admin/ | /api/admin/broker-infra/* | Feed Status, Connectivity | ✅ Ready |
| **Diagnostics** | admin/ | /api/admin/diagnostics/* | Logs, Metrics, Debug | ✅ Ready |
| **Notifications** | user/ | /api/trader/telegram/*, /whatsapp/* | Alerts, Bot Settings | ✅ Ready |
| **Emergency Exit** | execution/ | /api/execution/emergency-exit | Kill Switch, Stop All | ✅ Ready |
| **Setup Detection** | strategy/ | /api/strategies/setup-detection | Pattern Scanner, Alerts | ✅ Ready |
| **Paper Trading** | strategy/ | /api/trader/paper-trading | Virtual Account, Learning | ✅ Ready |
| **Margin Monitor** | admin/ | /api/admin/capital/* | Margin Utilization, Warnings | ✅ Ready |
| **Reconciliation** | admin/ | /api/admin/reconciliation/* | Recon Status, Variances | ✅ Ready |
| **Trade Recon** | admin/ | /api/admin/trade-recon/* | Trade Audit, Settlement | ✅ Ready |

---

## Quick Count

```
✅ 20 Major Features
✅ 50+ Controllers
✅ 280+ API Endpoints
✅ 100% Functional
✅ Ready for Production
```

---

## Key Data Points Per Feature

### 🎯 **Strategy Trading**
- Live instances (start, stop, pause)
- Real-time P&L tracking
- Signal execution monitoring
- Performance metrics

### 📦 **Orders & Execution**
- Place/modify/cancel orders
- Real-time order status
- Fill tracking
- Execution quality metrics

### 💼 **Portfolio**
- Current positions
- Holdings value
- Available margin
- Day & net P&L

### 📈 **Market Data**
- Real-time quotes
- Bid-ask spreads
- Trading volume
- OHLC charts

### 🛡️ **Risk**
- Position exposure
- Margin requirements
- Risk limits
- VaR calculations

### 📊 **Analytics**
- Win rate
- Drawdown
- Sharpe ratio
- Returns

### 👤 **User**
- Profile & settings
- Broker account sync
- API key management
- Notification preferences

### 🔧 **Admin**
- System health
- Feed status
- Operations monitoring
- Diagnostics & logs

---

## What's NOT in Release_v2

❌ No AI/ML predictions  
❌ No algorithmic recommendation engine  
❌ No advanced portfolio optimization  
❌ No multi-account management UI  
❌ No advanced charting (but OHLC available)  

---

## For UI: Use These Controllers

### **Trader Features** (User-facing)
1. `StrategyInstanceController` - See strategies, start/stop
2. `OrderExecutionController` - Place orders, view book
3. `PortfolioApiController` - View positions, margin
4. `MarketDataController` - Market quotes, watchlist
5. `TraderSelfController` - User profile, account info
6. `TraderBrokerController` - Broker details, margin
7. `BacktestController` - Run backtests
8. `BacktestJournalController` - View trade journal
9. `SignalExecutionDashboardController` - Watch signals

### **Admin Features** (Monitoring)
1. `AdminOperationsSnapshotController` - System status
2. `AdminBrokerInfrastructureController` - Feed status
3. `AdminRiskDashboardController` - Risk metrics
4. `AdminReadinessController` - Health checks
5. `AdminReconciliationController` - Trade reconciliation
6. `AdminSignalController` - Signal monitoring
7. `AdminOperationalDiagnosticsController` - Logs & debug

---

## Real-Time Data Streams Available

```
WebSocket Endpoints:
├─ /ws/market-data       → Real-time quotes
├─ /ws/signals           → Signal execution feed
├─ /ws/operations        → System events
├─ /ws/orders            → Order updates
└─ /ws/positions         → Position changes
```

---

## UI State Management Needed

```
State (Zustand):
├─ User (profile, auth)
├─ Portfolio (positions, margin)
├─ Orders (active orders, fills)
├─ Strategies (instances, stats)
├─ Signals (active, executed)
├─ RiskMetrics (exposure, limits)
└─ SystemStatus (health, feeds)

Data Fetching (TanStack Query):
├─ Cache strategy signals
├─ Invalidate on execution
├─ Refetch positions every 2s
└─ Stream market data via WebSocket
```

---

## Deployment: What Gets Deployed to new.stokr.in

```
Docker Compose Stack:
├─ stokr-ui (React/Vite)       → localhost/index.html
├─ stokr-api (Java Spring)     → localhost:8080
├─ PostgreSQL Database         → Persisted data
├─ Redis Cache                 → Session/quotes
└─ RabbitMQ Message Queue      → Signal routing

All UI endpoints → /api/* → Java backend (8080)
All WS endpoints → /ws/*  → Java backend (8080)
```

---

## .env.local Already Points to:
```
STOKR_BACKEND_ORIGIN=https://new.stokr.in
STOKR_API_PROXY_TARGET=https://new.stokr.in
```

**Ready to build & deploy!** ✅
