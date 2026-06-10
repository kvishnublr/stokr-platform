# Tomorrow's Testing Plan - Exit Criteria Verification

## 🎯 Objective
Test with 1 stock to verify ALL exit criteria work properly with the adjusted parameters.

**Test Date**: 2026-06-11 (Tomorrow)
**Test Time**: 9:15 AM - 3:30 PM IST
**Test Scope**: 1 Stock (Liquid equity recommended: RELIANCE, INFY, TCS, HDFC, ICICI)

---

## 📋 Strategy Parameters Active Tomorrow

### Entry Criteria
- **Market Hours**: 9:15 AM - 3:30 PM IST
- **AI Confidence Min**: 80% (changed from 90%)
- **Entry AI Score**: >= 80% (changed from 85%)
- **Max Positions**: 5 (no change)
- **Scan Interval**: Every 30 seconds

### Exit Criteria (MUST VERIFY ALL)
1. **Take Profit Exit**: +3.00% gain
2. **Stop Loss Exit**: -0.50% loss (tightened from 1.50%)
3. **AI Score Drop**: Exit when aiScore < 70%
4. **Opposite Signal**: Exit if opposite A+ signal appears
5. **Market Close**: Auto-exit at 3:30 PM IST

---

## ✅ Pre-Test Checklist (9:00 AM)

Before market opens, verify:

```
□ Application running: ps aux | grep java
□ Port 8080 accessible: curl http://173.249.55.84:8080/actuator/health
□ Admin dashboard loads: http://173.249.55.84:8080/admin
□ Database connected: Check logs for "Successfully applied X migrations"
□ Strategy enabled: A+ Scanner configured and running
□ Market data feeding: Check for candle updates
```

---

## 🧪 Test Execution Checklist

### DURING MARKET HOURS (9:15 AM - 3:30 PM)

#### 1. ENTRY SIGNAL TEST
```
GOAL: Verify that 80% AI score triggers entry

Actions:
□ Monitor logs for "A+ Scanner: Found X A+ setups detected (threshold: 80)"
□ Look for "A+ ENTRY: <SYMBOL> <SIDE> @ <PRICE> (aiScore: Z)"
□ Verify aiScore is >= 80% (not 85%)
□ Note entry price and exact timestamp

Expected Behavior:
✓ Signals appear with 80% confidence (more relaxed than 85%)
✓ Entries happen within 30 seconds of scan detection
✓ Entry time logged accurately
```

#### 2. STOP LOSS TEST
```
GOAL: Verify -0.50% stop loss triggers correctly

Actions:
□ Monitor active position P&L in real-time
□ Wait for position to drop to -0.50% (tight!)
□ Verify exit trigger logs show "HARD_SL"
□ Record exit time and exact P&L

Expected Behavior:
✓ Exit happens at exactly -0.50% ± 0.05%
✓ Exit reason: "Stop loss hit: -0.50%"
✓ No delay between trigger and exit
✓ Position closes cleanly

CRITICAL: If SL triggers too frequently (> 50% of exits):
→ Note this for adjustment (might increase to 0.70% after testing)
```

#### 3. TAKE PROFIT TEST
```
GOAL: Verify +3.00% take profit triggers correctly

Actions:
□ Monitor position when it reaches +2.5% (close to TP)
□ Verify it exits at +3.00% exactly
□ Record exit time and P&L

Expected Behavior:
✓ Exit happens at exactly +3.00% ± 0.05%
✓ Exit reason: "Profit target hit: +3.00%"
✓ Takes priority if both SL and TP conditions met
```

#### 4. AI SCORE DROP TEST
```
GOAL: Verify exit triggers when aiScore drops below 70%

Actions:
□ For active positions, monitor current AI score in terminal
□ Note when aiScore starts declining
□ Verify exit logs show "AI_SCORE_DROP" when score < 70%
□ Record how long position holds before exit

Expected Behavior:
✓ Exit happens immediately when aiScore < 70%
✓ Exit reason: "AI_SCORE_DROP: aiScore X→Y (below threshold 70)"
✓ Exit within 10 seconds of threshold breach
```

#### 5. OPPOSITE SIGNAL TEST
```
GOAL: Verify exit triggers when opposite signal appears

Actions:
□ If in BUY position, watch for SELL signal >= 85%
□ If in SELL position, watch for BUY signal >= 85%
□ Verify exit logs show "OPPOSITE_SIGNAL"
□ Record detection time vs exit time

Expected Behavior:
✓ Exit happens when opposite signal appears
✓ Exit reason: "Opposite A+ signal detected"
✓ Exits within 10 seconds of signal detection
✓ Doesn't wait for SL/TP if opposite signal fires
```

#### 6. MARKET CLOSE TEST
```
GOAL: Verify auto-exit at 3:30 PM IST

Actions:
□ Keep any open positions until 3:29 PM
□ Watch logs at 3:30 PM exactly
□ Verify logs show "MARKET_CLOSE" exit reason
□ Confirm all positions closed by 3:31 PM

Expected Behavior:
✓ All positions auto-exit at 3:30 PM IST
✓ Exit reason: "Market close auto-exit"
✓ No positions held after market close
```

---

## 📊 Data Collection During Test

For EACH trade, record:

```
Trade #1:
  Entry Signal: SYMBOL | Side: BUY/SELL | Price: | aiScore: | Time:
  Exit Reason: SL/TP/AI_DROP/OPPOSITE/MARKET_CLOSE
  Entry Price: | Exit Price: | PnL: | PnL%: | Duration:
  
Trade #2:
  [Same format...]

Trade #N:
  [Same format...]
```

---

## 📈 Summary Metrics to Collect

By end of day, record:

```
Total Trades: ___
Successful Trades (SL/TP/AI_DROP): ___
AI Score Drop Exits: ___
Opposite Signal Exits: ___
Market Close Exits: ___
Failed Exits: ___ (if any)

Total Wins (TP): ___
Total Losses (SL): ___
Net PnL: ___
Win Rate: ___

Average Duration: ___
Longest Trade: ___
Shortest Trade: ___

SL Hit Frequency: __% (if >50%, plan to increase to 0.70%)
TP Hit Frequency: __% (should be <20% ideally)
AI_DROP Frequency: __% (should be 20-30%)
OPPOSITE Frequency: __% (should be 10-20%)
```

---

## 🚨 Critical Issues to Watch For

```
BLOCKER ISSUES (Stop testing if any occur):
❌ Application crashes/restarts
❌ Database disconnection
❌ Orders placed but no exits (stuck positions)
❌ Opposite exit detecting same signal (duplicate exits)

SEVERE ISSUES (Log but continue):
⚠️ Exit triggers but position not closed (stuck)
⚠️ P&L calculation wrong
⚠️ SL triggers too frequently (>60% of exits)
⚠️ TP never triggers
⚠️ AI score not updating in real-time

INFORMATION ISSUES (Note but not critical):
ℹ️ Latency between trigger and exit (>5 sec)
ℹ️ Logs not showing exit reason
ℹ️ UI not updating in real-time
```

---

## 📍 Key Monitoring Points

### Real-Time Log Monitoring
```bash
# SSH to server and run:
ssh root@173.249.55.84

# Monitor in one terminal:
tail -f /app/stokr-bootstrap.log | grep 'A+ ENTRY\|A+ EXIT\|ERROR'

# Or watch specific:
tail -f /app/stokr-bootstrap.log | grep 'HARD_SL\|HARD_TP\|AI_SCORE_DROP\|OPPOSITE_SIGNAL\|MARKET_CLOSE'
```

### Admin Dashboard
```
Access: http://173.249.55.84:8080/admin
- Service Health Panel: Should show all GREEN
- Queue Monitoring: Should show trading.signals queue activity
- Signal Lifecycle: Search by signal ID to see full timeline
```

---

## 🎯 Pass/Fail Criteria

### PASS Criteria (All Must Be True)
- ✅ All 5 exit types trigger at least once
- ✅ No stuck positions (all exit properly)
- ✅ Exit timing within 10 seconds of trigger
- ✅ P&L calculations correct
- ✅ No application crashes
- ✅ Market close auto-exit works

### FAIL Criteria (Any True = FAIL)
- ❌ Any exit type fails to trigger
- ❌ Position stuck without exit
- ❌ Exit time > 30 seconds after trigger
- ❌ P&L incorrect
- ❌ Application crashes
- ❌ SL triggers > 70% of time (need adjustment)

---

## 📝 Testing Form

Fill this out as you test:

```
TEST DATE: 2026-06-11
TESTER: [Your name]
SYMBOL: [Stock tested]

Morning (9:15-11:30):
  Entry Signals: ___
  Successful Exits: ___
  Issues: [Note any]

Mid-day (11:30-14:30):
  Entry Signals: ___
  Successful Exits: ___
  Issues: [Note any]

Closing (14:30-15:30):
  Entry Signals: ___
  Successful Exits: ___
  Issues: [Note any]

FINAL RESULT: PASS / FAIL / NEEDS ADJUSTMENT

Reason: [If FAIL or adjustment needed]

Notes: [Any observations for improvement]
```

---

## 🔧 Immediate Adjustments If Needed

### If SL Triggers Too Frequently (> 70%)
```
Change from: -0.50% (50 bps)
Change to: -0.70% (70 bps)

Location: APlusStrategyConfig.java
Line: hardSlPct = BigDecimal.valueOf(0.70);

Then: Rebuild and redeploy
```

### If TP Never Triggers
```
Check if: Stock is not moving enough
Check if: Multiple exits happening before TP

Adjustment: Lower TP from 3.00% to 2.00%
(But recommend keeping at 3% for first test)
```

### If AI Score Not Updating
```
Check: Market data service health
Check: A+ Scanner service logs
Check: Signal generation logs

Might need: Restart market data service
```

---

## 📞 Emergency Procedures

### If Application Crashes
```bash
SSH to server:
ssh root@173.249.55.84

Restart:
pkill -9 java
sleep 3
cd /app && nohup java -XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 \
  -Xms256m -Duser.timezone=Asia/Kolkata -jar stokr-bootstrap.jar &

Verify:
ps aux | grep java
```

### If Database Disconnects
```bash
Check connection:
PGPASSWORD=root psql -h localhost -U postgres -d stokr_platform -c 'SELECT 1'

If fails:
docker exec stokr-postgres psql -U postgres -d stokr_platform -c 'SELECT 1'
```

---

## 📊 Success Indicators

By end of day, you should see:

```
✅ Minimum 5-10 trades
✅ All 5 exit types triggered at least once
✅ No stuck positions
✅ Consistent P&L calculations
✅ Smooth auto-exits at 3:30 PM
✅ No application errors
```

---

## 🎉 After Testing

1. **Collect all data** from the form above
2. **Review exit timing** - is 10 seconds acceptable?
3. **Check SL frequency** - if >70%, plan adjustment
4. **Note any errors** for debugging
5. **Create test report** with results

Then we proceed to:
- Phase 2: Real data integration
- Phase 3: Performance optimization
- Phase 4: Auto-scaling and advanced features

---

## 📌 Quick Reference

### Log Patterns to Look For

```
Entry: "A+ ENTRY: <SYMBOL> <SIDE> @ <PRICE> (aiScore: <SCORE>)"
Take Profit: "A+ EXIT: <SYMBOL> | PnL: +<X> (3.XX%) | Reason: HARD_TP"
Stop Loss: "A+ EXIT: <SYMBOL> | PnL: -<X> (0.XX%) | Reason: HARD_SL"
AI Drop: "A+ EXIT: <SYMBOL> | Reason: AI_SCORE_DROP: aiScore <X>→<Y>"
Opposite: "A+ EXIT: <SYMBOL> | Reason: Opposite A+ signal detected"
Market Close: "A+ EXIT: <SYMBOL> | Reason: Market close auto-exit"
```

### Admin Dashboard Endpoints

```
Health: http://173.249.55.84:8080/actuator/health
Services: http://173.249.55.84:8080/api/v1/admin/health/services
Queues: http://173.249.55.84:8080/api/v1/admin/health/queues
Signal: http://173.249.55.84:8080/api/v1/admin/signals/{signal-id}/lifecycle
```

---

**Status**: ✅ Ready for testing tomorrow at 9:15 AM IST

All exit criteria are implemented and active.
Go test and verify everything works!

Good luck! 🚀
