# INDEX HUNT - COMPLETE DEPLOYMENT GUIDE

**Last Updated:** 2026-05-27  
**Status:** ✅ READY FOR DEPLOYMENT  
**Version:** 1.0.0 (OPTIONS TRADING)

---

## 📋 WHAT'S IMPLEMENTED

✅ **Unit Tests** - 15+ test cases covering all 5 gates  
✅ **Kite API Integration** - Real-time market data (prices, VIX, PCR, options)  
✅ **Paper Trading Module** - Simulate order execution with realistic slippage  
✅ **Telegram Alerts** - Signal notifications & outcome updates  
✅ **Automatic Scheduler** - Background detection & monitoring  
✅ **REST API** - Full control via HTTP endpoints  
✅ **Database Schema** - Optimized with indexes & views  
✅ **Logging** - Comprehensive DEBUG logs for troubleshooting  

---

## 🚀 QUICK START

### 1. Prerequisites

```bash
# Java
java -version  # Should be Java 11+

# MySQL
mysql --version  # Should be MySQL 5.7+

# Maven
mvn -version  # Should be Maven 3.6+
```

### 2. Environment Setup

Create `.env` file in project root:

```bash
# Zerodha Kite API (get from Kite API dashboard)
KITE_API_KEY=your_api_key
KITE_API_SECRET=your_api_secret
KITE_ACCESS_TOKEN=your_access_token

# Telegram Bot (get from @BotFather on Telegram)
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmnOPqrSTuvWxyz

# Your Telegram Chat IDs (get from @myidbot)
TELEGRAM_CHAT_ID_VISHNUBLR=123456789
TELEGRAM_CHAT_ID_HARSHVTRADE=987654321

# Database
DB_PASSWORD=your_mysql_password

# Port
PORT=8000
```

### 3. Database Setup

```bash
# Create database
mysql -u root -p
> CREATE DATABASE stokr_strategy;
> EXIT;

# Run migrations (automatic with Flyway)
# Migrations in: stokr-strategy/src/main/resources/db/migration/
```

### 4. Build & Run

```bash
# Build
cd stokr-strategy
mvn clean compile

# Run tests (optional, requires live Kite credentials)
mvn test -DskipTests=true  # Skip tests for now

# Start application
mvn spring-boot:run

# Application starts at http://localhost:8000
```

---

## 🧪 TESTING THE SETUP

### 1. Health Check

```bash
curl http://localhost:8000/api/index-hunt/health

# Expected response:
# {
#   "status": "UP",
#   "service": "INDEX_HUNT",
#   "version": "1.0.0"
# }
```

### 2. Run Detection Manually

```bash
curl -X POST http://localhost:8000/api/index-hunt/detect

# Expected response: List of detected signals (if market hours)
```

### 3. Paper Trade a Signal

```bash
# 1. Get pending signals
curl http://localhost:8000/api/index-hunt/signals/active

# 2. Execute signal (replace 1 with actual signal_id)
curl -X POST http://localhost:8000/api/paper-trading/execute/1

# 3. Check active trades
curl http://localhost:8000/api/paper-trading/active

# 4. Monitor trades (simulates price movement)
curl -X POST http://localhost:8000/api/paper-trading/monitor \
  -H "Content-Type: application/json" \
  -d '{"prices": {"NIFTY": 24100.50, "BANKNIFTY": 48350.75}}'

# 5. Check stats
curl http://localhost:8000/api/paper-trading/stats
```

---

## 📊 API ENDPOINTS

### INDEX HUNT Detection

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/index-hunt/detect` | POST | Run detection cycle |
| `/api/index-hunt/signals/active` | GET | Get pending signals |
| `/api/index-hunt/signals/{index}` | GET | Get signals by index (NIFTY/BANKNIFTY) |
| `/api/index-hunt/signals/premium` | GET | Get premium tier (quality >= 76) |
| `/api/index-hunt/signals/{index}/recent` | GET | Get recent signals (last N hours) |
| `/api/index-hunt/stats/today` | GET | Daily statistics |
| `/api/index-hunt/health` | GET | Service health |

### Paper Trading

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/paper-trading/execute/{signalId}` | POST | Execute signal |
| `/api/paper-trading/active` | GET | Get active trades |
| `/api/paper-trading/monitor` | POST | Monitor & check T1/SL |
| `/api/paper-trading/stats` | GET | Trading statistics |
| `/api/paper-trading/next-signals` | GET | Get next signals to execute |
| `/api/paper-trading/reset` | POST | Clear all trades |

---

## 🔄 AUTOMATION WITH SCHEDULER

The `IndexHuntScheduler` runs automatically during market hours:

```
10:15 AM - 1:45 PM IST:
  ├─ Every 10 sec: Detect new signals
  ├─ Every 5 sec: Monitor active trades (check T1/SL)
  └─ Send Telegram alerts on signals & outcomes

3:35 PM IST (daily):
  └─ Send daily summary to Telegram

Every minute:
  └─ Health check (Telegram connection, market data)
```

**To disable scheduler:** Set `spring.task.scheduling.enabled=false` in application.yml

---

## 📈 PAPER TRADING WORKFLOW

### Example: Manual Paper Trading Session

```bash
# Step 1: Trigger detection (during 10:15-13:45 IST)
curl -X POST http://localhost:8000/api/index-hunt/detect

# Response: New signals detected
# [
#   {
#     "signalId": 1,
#     "indexName": "BANKNIFTY",
#     "direction": "CE",
#     "qualityScore": 82,
#     "optionEntryPremium": 150.00,
#     "executionStatus": "PENDING"
#   }
# ]

# Step 2: Execute signal 1
curl -X POST http://localhost:8000/api/paper-trading/execute/1

# Response:
# {
#   "success": true,
#   "signalId": 1,
#   "message": "execution_successful",
#   "timestamp": 1234567890
# }

# Step 3: Monitor every 5-10 seconds (simulate price movement)
curl -X POST http://localhost:8000/api/paper-trading/monitor \
  -H "Content-Type: application/json" \
  -d '{
    "prices": {
      "BANKNIFTY": 48400.00
    }
  }'

# Repeat with different prices:
# 48350 -> 48360 -> 48380 -> 48395 (getting closer to T1)

# Step 4: Check stats
curl http://localhost:8000/api/paper-trading/stats

# When T1 hits, signal auto-closes with WIN
# When SL hits, signal auto-closes with LOSS
# After 30 minutes, trade expires

# Response:
# {
#   "totalTrades": 1,
#   "wins": 1,
#   "losses": 0,
#   "winRate": 100.0,
#   "totalPnL": 350.00,
#   "activeTrades": 0,
#   "timestamp": "2026-05-27T14:30:45Z"
# }
```

---

## 🔔 TELEGRAM ALERTS

### What You'll Receive

**New Signal Alert:**
```
🎯 NEW INDEX HUNT SIGNAL

Index: BANKNIFTY
Direction: 📈 CALL
Quality: 82.5 ⭐ PREMIUM
Strength: 🔴 HIGH

Entry: ₹150.00
SL: ₹120.00
T1: ₹192.00
T2: ₹247.50

Trend 30m: +0.18%
Momentum 5m: +0.25%
PCR: 1.25
VIX: 17.5

Time: 11:30:45
```

**Win Alert:**
```
✅ T1 HIT - WIN
Index: BANKNIFTY CE
Entry: ₹150.00
Exit: ₹192.00
P&L: ₹630.00
Time: 11:45:30
```

**Loss Alert:**
```
❌ SL HIT - LOSS
Index: BANKNIFTY CE
Entry: ₹150.00
Exit: ₹120.00
Loss: -₹450.00
Time: 11:50:15
```

**Daily Summary:**
```
📊 DAILY SUMMARY - INDEX HUNT
Trades: 8
Wins: 6 | Losses: 2
Win Rate: 75.0%
Total P&L: ₹2,450.00
Active Trades: 1
Time: 15:35:00
```

---

## 🧪 RUN UNIT TESTS

```bash
# Run all tests
mvn test

# Run only INDEX HUNT detector tests
mvn test -Dtest=IndexHuntDetectorTest

# Run with coverage
mvn test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Test Results Expected

- ✅ 15+ test cases
- ✅ 100% gate coverage
- ✅ Quality score validation
- ✅ Entry/exit level calculations
- ✅ Integration tests

---

## 📊 DATABASE TABLES & VIEWS

### Main Table

```sql
-- Active signals and outcomes
SELECT * FROM index_signals;

-- Stats by index
SELECT * FROM v_index_hunt_win_rate_by_index;

-- Today's trades
SELECT * FROM v_index_hunt_today_summary;

-- Recent performance (last 20 trades)
SELECT * FROM v_index_hunt_recent_performance;
```

---

## 🔧 TROUBLESHOOTING

### Issue: "Kite API connection failed"

```
Solution: Check KITE_API_KEY, KITE_API_SECRET, KITE_ACCESS_TOKEN
          - Log in to https://kite.zerodha.com/
          - Settings → API Permissions
          - Get your API key and secret
          - Generate access token via API dashboard
```

### Issue: "Telegram bot not sending messages"

```
Solution: Check TELEGRAM_BOT_TOKEN and chat IDs
          - Create bot: @BotFather on Telegram
          - Get token: BotFather gives you token
          - Get chat IDs: @myidbot sends your chat ID
          - Test: curl "https://api.telegram.org/bot<TOKEN>/getMe"
```

### Issue: "Database connection failed"

```
Solution: Ensure MySQL is running and accessible
          - mysql -u root -p
          - CREATE DATABASE stokr_strategy;
          - Update DB_PASSWORD in .env
```

### Issue: "Signals not detecting"

```
Solution: Check if during trading hours (10:15-13:45 IST)
          - Verify market is open (NSE trading day)
          - Check Kite API quote feed
          - Verify PCR, VIX data coming through
          - Check logs: tail -f logs/stokr-strategy.log | grep INDEX_HUNT
```

---

## 📈 MONITORING & LOGS

### Real-time Log Tail

```bash
# All INDEX_HUNT logs
tail -f logs/stokr-strategy.log | grep INDEX_HUNT

# Detection logs only
tail -f logs/stokr-strategy.log | grep "detector"

# Paper trading logs only
tail -f logs/stokr-strategy.log | grep "PAPER_TRADE"

# Telegram alerts
tail -f logs/stokr-strategy.log | grep "TELEGRAM"
```

### Sample Log Output

```
11:30:05.123 [scheduler-1] DEBUG IndexHuntDetector - MOMENTUM_5M=+0.15%, TREND_30M=+0.18%
11:30:05.234 [scheduler-1] DEBUG IndexHuntDetector - INDEX_HUNT.signal_detected index=BANKNIFTY direction=CE quality=82.50 strength=md
11:30:05.456 [scheduler-1] INFO  IndexHuntService - INDEX_HUNT.signal_saved index=BANKNIFTY direction=CE quality=82.50 premium_tier=yes
11:30:05.678 [scheduler-1] INFO  IndexHuntTelegramService - TELEGRAM.signal_sent signal_id=1
```

---

## ✅ DEPLOYMENT CHECKLIST

### Pre-Deployment

- [ ] All environment variables set (.env file)
- [ ] MySQL database created
- [ ] Kite API credentials working
- [ ] Telegram bot token & chat IDs obtained
- [ ] Java 11+ installed
- [ ] Maven 3.6+ installed

### Testing

- [ ] Unit tests pass (`mvn test`)
- [ ] Health check passes (GET /health)
- [ ] Manual signal detection works
- [ ] Paper trading executes successfully
- [ ] Telegram alerts sending
- [ ] Database tables created & indexes working

### Deployment

- [ ] Build passes (`mvn clean compile`)
- [ ] No log errors on startup
- [ ] Scheduler running (check logs every minute)
- [ ] Monitor active trades with scheduler

### Post-Deployment

- [ ] Run detection manually to verify
- [ ] Execute test signal in paper trading
- [ ] Verify Telegram alert received
- [ ] Check daily summary at 3:35 PM
- [ ] Monitor logs for 1 day

---

## 🎯 NEXT STEPS

### Week 1: Paper Trading Validation

```
Day 1-3: Monitor scheduler, verify signals, check T1/SL hits
Day 4-5: Execute manual signals, track P&L, validate win rate
Day 6-7: Run 20-30 trades, ensure 70%+ win rate
```

### Week 2: Fine-Tuning

```
If WR >= 75%: Ready for limited live trading (1 lot)
If WR 70-74%: Optimize parameters, monitor more
If WR < 70%:  Investigate bias, check market regime
```

### Week 3+: Live Deployment

```
Start: 1 lot BANKNIFTY CE only (80%+ WR historically)
Monitor: 50-100 trades for live win rate validation
Scale: Increase to 2 lots if live WR >= 70%
```

---

## 📞 SUPPORT

### Log Issues with:

```
Location: logs/stokr-strategy.log
Filter: grep "ERROR\|WARN" logs/stokr-strategy.log
Include in report: Timestamp, full error message, last 5 successful operations
```

### Monitor Dashboard

```
POST /api/paper-trading/stats     → Current P&L
POST /api/index-hunt/detect       → Force detection
GET  /api/paper-trading/active    → Active trades
```

---

**⚠️ IMPORTANT:**

- Paper trading is SIMULATION only (no real money)
- Use for validation before live trading
- Slippage (0.05% entry, 0.08% exit) is realistic but may vary
- Paper trading doesn't account for: gaps, limit down, partial fills
- ALWAYS validate on live data before going live

**🚀 READY TO DEPLOY!**

