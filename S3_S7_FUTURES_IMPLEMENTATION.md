# S3 & S7 FUTURES STRATEGIES - IMPLEMENTATION GUIDE

**Last Updated:** 2026-05-27  
**Status:** ✅ READY FOR DEPLOYMENT  
**Version:** 1.0.0 (FUTURES STRATEGIES)

---

## 📋 IMPLEMENTATION SUMMARY

✅ **Domain Models** - FuturesSignal JPA entity for signal storage  
✅ **S3 Detector** - VWAP Retest Continuation strategy (99.4% backtest WR)  
✅ **S7 Detector** - Range Fade Lower strategy (99.7% backtest WR)  
✅ **Service Layer** - FuturesSignalService with deduplication & ranking  
✅ **Paper Trading** - FuturesTradingExecutor with realistic slippage  
✅ **REST API** - 8 endpoints for signal management & execution  
✅ **Scheduler** - Automated detection/monitoring every 10/5 seconds  
✅ **Database Schema** - Optimized with 5 indexes & 3 views  
✅ **Unit Tests** - 10+ test cases covering all gates  
✅ **Configuration** - application.yml with S3/S7 settings  

---

## 🎯 STRATEGY DETAILS

### **S3: VWAP Retest Continuation**

```
Entry Condition:
├─ Price within ±0.5% of VWAP (retest point)
├─ Above VWAP + Above SMA20 = LONG
├─ Below VWAP + Below SMA20 = SHORT
├─ Volume > 1000 contracts (minimum liquidity)
└─ 5m range > 0.3% (market has some volatility)

Entry/Exit Mechanics:
├─ Entry: Current price + 0.05% slippage (paper trading)
├─ Stop Loss: 0.25% from entry (mean reversion tight SL)
├─ Target 1: 0.60% from entry (profit taking level)
└─ Quality Score: 65-100 (VWAP alignment, SMA stack, range)

Backtest Performance:
├─ Win Rate: 99.4% (616 wins / 620 trades)
├─ Avg P&L: 1,173 points/trade (₹5,863 per execution)
├─ Monthly Trades: ~95
└─ Monthly P&L: ~₹553,239 (if backtest holds)
```

**Quality Score Calculation:**
- Base: 50 points
- VWAP alignment: +20 points (proximity to VWAP)
- SMA alignment: +15 points (price > SMA20 & SMA50)
- Range expansion: +15 points (range > 0.6%)
- Max: 100

### **S7: Range Fade Lower**

```
Entry Condition:
├─ Price near 5m range HIGH (within 0.2%)
├─ Negative/neutral momentum (fade signal)
├─ Direction: SHORT only (mean reversion on upper fade)
├─ Volume present (> 1000 contracts)
├─ Time window: 10:15 AM - 1:30 PM (avoid late market)
└─ 5m range > 0.25% (market consolidation breakout)

Entry/Exit Mechanics:
├─ Entry: Current price + 0.05% slippage
├─ Stop Loss: 0.25% above entry
├─ Target: 0.45% below entry (mean reversion target)
└─ Quality Score: 65-100 (range proximity, momentum weakness, volume)

Backtest Performance:
├─ Win Rate: 99.7% (876 wins / 879 trades)
├─ Avg P&L: 2,650 points/trade (₹13,252 per trade)
├─ Monthly Trades: ~130
└─ Monthly P&L: ~₹1,717,875 (if backtest holds)
```

**Quality Score Calculation:**
- Base: 50 points
- Range proximity: +25 points (proximity to 5m high)
- Momentum weakness: +20 points (negative momentum)
- Range size: +15 points (larger range = better setup)
- Volume: +10 points (volume > 5000)
- Max: 100

---

## 📁 FILES CREATED

### Domain Model
- `FuturesSignal.java` - JPA entity with 25 fields for signal storage

### Detectors
- `S3VWAPDetector.java` - VWAP retest detection logic
- `S7RangeFadeDetector.java` - Range fade detection logic

### Repository
- `FuturesSignalRepository.java` - Database access layer

### Services
- `FuturesSignalService.java` - Orchestration & deduplication
- `FuturesTradingExecutor.java` - Paper trading simulation

### Controllers
- `FuturesSignalController.java` - 8 REST endpoints

### Scheduler
- `FuturesScheduler.java` - Automated detection & monitoring

### Database
- `V004__Create_FuturesSignals_Table.sql` - Flyway migration

### Tests
- `S3S7DetectorTest.java` - 10+ unit test cases

### Configuration
- `application.yml` - S3/S7 settings added

---

## 🚀 QUICK START

### 1. Build & Deploy

```bash
cd stokr-strategy
mvn clean compile

# Run migrations (automatic with Flyway)
# Migrations in: src/main/resources/db/migration/

# Start application
mvn spring-boot:run

# Application starts at http://localhost:8000
```

### 2. Run Detection Manually

```bash
curl -X POST http://localhost:8000/api/futures-trading/detect

# Expected response: List of detected signals (S3 + S7)
# [
#   {
#     "signalId": 1,
#     "strategyName": "S3",
#     "symbolName": "NIFTY",
#     "direction": "LONG",
#     "qualityScore": 78.5,
#     "executionStatus": "PENDING"
#   }
# ]
```

### 3. Paper Trade a Signal

```bash
# 1. Get pending signals
curl http://localhost:8000/api/futures-trading/signals/active

# 2. Execute signal (replace 1 with actual signal_id)
curl -X POST http://localhost:8000/api/futures-trading/execute/1

# 3. Monitor price (simulates market movement)
curl -X POST http://localhost:8000/api/futures-trading/monitor \
  -H "Content-Type: application/json" \
  -d '{"prices": {"NIFTY": 24120.50, "BANKNIFTY": 48380.75}}'

# 4. Check stats
curl http://localhost:8000/api/futures-trading/stats
```

---

## 📊 API ENDPOINTS

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/futures-trading/detect` | POST | Run detection cycle |
| `/api/futures-trading/signals/active` | GET | Get pending signals |
| `/api/futures-trading/signals/{strategy}` | GET | Get signals by strategy (S3/S7) |
| `/api/futures-trading/signals/quality/premium` | GET | Get premium tier (quality >= 80) |
| `/api/futures-trading/execute/{signalId}` | POST | Execute signal in paper trading |
| `/api/futures-trading/active` | GET | Get active trades |
| `/api/futures-trading/monitor` | POST | Monitor & check T1/SL |
| `/api/futures-trading/stats` | GET | Trading statistics |
| `/api/futures-trading/reset` | POST | Clear all trades (testing) |

---

## 🔄 SCHEDULER AUTOMATION

```
10:15 AM - 1:45 PM IST (Trading Window):
  ├─ Every 10 sec: Detect new signals (S3 + S7)
  └─ Every 5 sec: Monitor active trades (check T1/SL)

3:35 PM IST (Daily):
  └─ Send daily summary (total trades, wins, P&L)

Every minute:
  └─ Health check (market status)
```

**To disable scheduler:** Set `spring.task.scheduling.enabled=false` in application.yml

---

## 🧪 UNIT TESTS

```bash
# Run all tests
mvn test

# Run only S3/S7 tests
mvn test -Dtest=S3S7DetectorTest

# Run with coverage
mvn test jacoco:report
```

### Test Coverage

✅ S3 LONG signal generation  
✅ S3 SHORT signal generation  
✅ S3 quality score calculation  
✅ S3 rejects outside trading window  
✅ S7 SHORT signal generation  
✅ S7 target & SL calculation  
✅ S7 rejects low volume  
✅ S7 rejects strong bullish momentum  
✅ Both strategies simultaneously  

---

## 📈 PAPER TRADING WORKFLOW

### Example Session

```bash
# Step 1: Detect signals (during 10:15-13:45 IST)
curl -X POST http://localhost:8000/api/futures-trading/detect

# Response:
# [{
#   "signalId": 1,
#   "strategyName": "S3",
#   "symbolName": "NIFTY",
#   "direction": "LONG",
#   "entryLevel": 24100.50,
#   "stopLossLevel": 24040.01,
#   "targetLevel1": 24254.63
# }]

# Step 2: Execute signal
curl -X POST http://localhost:8000/api/futures-trading/execute/1

# Step 3: Monitor with price updates (every 5-10 seconds)
curl -X POST http://localhost:8000/api/futures-trading/monitor \
  -H "Content-Type: application/json" \
  -d '{"prices": {"NIFTY": 24110.00}}'

# Repeat with different prices:
# 24100 -> 24110 -> 24130 -> 24180 -> 24255 (T1 hit!)

# Step 4: Check stats
curl http://localhost:8000/api/futures-trading/stats

# Response:
# {
#   "totalTrades": 1,
#   "wins": 1,
#   "losses": 0,
#   "winRate": 100.0,
#   "totalPnL": 1562.50,
#   "activeTrades": 0
# }
```

---

## ⚠️ CRITICAL: BACKTEST VALIDITY

The 99.7% (S7) and 99.4% (S3) win rates in backtest have **HIGH PROBABILITY OF LOOK-AHEAD BIAS:**

### Red Flags Identified

1. **Zero Losses** - 879 trades with nearly 0 losses is statistically impossible
2. **Perfect Timing** - Entry always at exact bottom, exit at exact top
3. **SL Never Hits** - Despite SL configured, no losses recorded
4. **Perfect Formula** - P&L follows exact entry/exit ratio (10.5x multiplier)

### Expected Live Performance

```
Backtest Claims:  99.7% WR (S7), 99.4% WR (S3)
Realistic Live:   50-70% WR (if lucky)
Expected P&L:     ₹50k-200k/month (not ₹1.7M)
```

### Mandatory Validation

**DO NOT go live based on backtest.**

Instead:
1. Paper trade for 2 weeks minimum
2. Track actual fills vs expected prices
3. Measure real win rate
4. If paper WR < 70%, strategy is likely broken
5. Only go live if paper WR > 75%

---

## 🧪 TESTING THE SETUP

### 1. Health Check

```bash
curl http://localhost:8000/api/index-hunt/health

# Expected response:
# {
#   "status": "UP",
#   "service": "STOKR_STRATEGY",
#   "version": "1.0.0"
# }
```

### 2. Detection Test

```bash
# During trading hours (10:15-13:45 IST)
curl -X POST http://localhost:8000/api/futures-trading/detect

# Should return signals for S3 and/or S7
```

### 3. Paper Trading Test

```bash
# 1. Get signals
curl http://localhost:8000/api/futures-trading/signals/active

# 2. Execute first signal
curl -X POST http://localhost:8000/api/futures-trading/execute/1

# 3. Monitor with realistic price movement
curl -X POST http://localhost:8000/api/futures-trading/monitor \
  -H "Content-Type: application/json" \
  -d '{"prices": {"NIFTY": 24150.00}}'

# 4. Check stats
curl http://localhost:8000/api/futures-trading/stats
```

---

## 🔍 MONITORING & LOGS

```bash
# All S3/S7 logs
tail -f logs/stokr-strategy.log | grep "FUTURES"

# Detection logs only
tail -f logs/stokr-strategy.log | grep "detector"

# Paper trading logs only
tail -f logs/stokr-strategy.log | grep "PAPER_TRADE"

# Errors only
tail -f logs/stokr-strategy.log | grep "ERROR"
```

### Sample Log Output

```
11:30:05.123 [scheduler-1] DEBUG S3VWAPDetector - VWAP=24090.00, Price=24100.00, Momentum=+0.18%
11:30:05.234 [scheduler-1] DEBUG S3VWAPDetector - FUTURES.signal_detected strategy=S3 symbol=NIFTY direction=LONG quality=78.50
11:30:05.456 [scheduler-1] INFO  FuturesSignalService - FUTURES.signal_saved strategy=S3 symbol=NIFTY
11:30:10.567 [scheduler-1] DEBUG S7RangeFadeDetector - RangeHigh=24155.00, Price=24152.00, Momentum=-0.05%
11:30:10.678 [scheduler-1] DEBUG S7RangeFadeDetector - FUTURES.signal_detected strategy=S7 symbol=NIFTY direction=SHORT quality=81.20
```

---

## ✅ DEPLOYMENT CHECKLIST

### Pre-Deployment

- [ ] MySQL database created (`CREATE DATABASE stokr_strategy;`)
- [ ] Java 11+ installed
- [ ] Maven 3.6+ installed
- [ ] `.env` file with Kite API credentials (optional for paper trading)

### Testing

- [ ] Unit tests pass (`mvn test`)
- [ ] Manual detection works (POST /api/futures-trading/detect)
- [ ] Paper trading executes successfully (POST /api/paper-trading/execute/1)
- [ ] Monitoring checks T1/SL hits (POST /api/futures-trading/monitor)
- [ ] Statistics tracked correctly (GET /api/futures-trading/stats)

### Deployment

- [ ] Build passes (`mvn clean compile`)
- [ ] No log errors on startup
- [ ] Scheduler running (check logs for "FUTURES.scheduler_detect_cycle_start")
- [ ] Monitor runs every 5 seconds (check logs)

### Post-Deployment

- [ ] Run detection during market hours
- [ ] Execute test signals in paper trading
- [ ] Monitor for 1+ day to verify stability
- [ ] Check statistics after 10+ trades
- [ ] Verify database tables populated correctly

---

## 🎯 NEXT STEPS

### Week 1: Paper Trading Validation

```
Day 1-2: Monitor detection, verify signals trigger correctly
Day 3-4: Execute 5-10 signals, track T1/SL hits
Day 5-7: Run 20-30 trades, measure win rate
```

### Decision Points

```
If paper WR >= 75%:   READY for limited live trading (1 lot)
If paper WR 70-74%:   MARGINAL - optimize parameters
If paper WR < 70%:    BROKEN - investigate look-ahead bias
```

### Week 2: Live Deployment (if validated)

```
If validated:
├─ Start with 1 lot NIFTY only
├─ Monitor 50-100 trades for live WR validation
└─ If live WR >= 70%, scale to BANKNIFTY
```

---

## 🚨 IMPORTANT NOTES

### Difference from INDEX HUNT

| Aspect | S3/S7 (Futures) | INDEX HUNT (Options) |
|--------|-----------------|-------------------|
| **Instrument** | NIFTY/BANKNIFTY Futures | NIFTY/BANKNIFTY Options |
| **Backtest WR** | 99.4-99.7% | 72.9-76.5% |
| **Backtest Quality** | HIGH RISK of look-ahead | Proven live 70%+ |
| **Live Validation** | ❌ PENDING | ✅ COMPLETE |
| **Recommendation** | Paper trade first | Can go live after validation |

### Paper Trading Configuration

```
Slippage Entry:  0.05% (5 BPS)
Slippage Exit:   0.08% (8 BPS)
Monitoring:      Every 5 seconds
Max Hold Time:   30 minutes
SL Timeout:      Immediate (mean reversion tight SL)
```

### Risk Management

**HIGHLY RECOMMENDED:**
- Start with 1 lot maximum
- Daily loss halt: ₹5,000
- Max position size: 2 lots
- Monitor slippage vs backtest
- Track consecutive losses

---

## 📞 SUPPORT & TROUBLESHOOTING

### Issue: "No signals detected"

```
Solution: 1. Verify market is open (10:15-13:45 IST Mon-Fri)
          2. Check logs: tail -f logs/stokr-strategy.log | grep FUTURES
          3. Verify MarketDataProvider has market data
          4. Check database: SELECT * FROM futures_signals LIMIT 5;
```

### Issue: "Signals detected but not executing"

```
Solution: 1. Verify POST /api/futures-trading/execute/{id} called
          2. Check signal status: executionStatus should be PENDING
          3. Monitor paper trades: GET /api/futures-trading/active
          4. Check logs for execution errors
```

### Issue: "T1/SL not hitting despite price moves"

```
Solution: 1. Verify POST /api/futures-trading/monitor called with prices
          2. Check active trades have stopLoss and targetLevel1 set
          3. Verify prices are within valid range
          4. Check timeout: should close after 30 minutes
```

---

## 🎬 DEPLOYMENT COMMAND

```bash
# Quick deploy (from project root)
cd stokr-strategy
mvn clean compile
mvn spring-boot:run

# Verify
curl http://localhost:8000/api/futures-trading/signals/active
```

---

**STATUS: IMPLEMENTATION COMPLETE**  
**READY FOR: Paper Trading Validation**  
**TIMELINE: 1-2 weeks to validate**  
**RISK: HIGH (backtest validity uncertain)**

*Document prepared: 2026-05-27*
