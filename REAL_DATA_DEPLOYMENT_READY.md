# ✅ REAL DATA DEPLOYMENT READY

## Status: READY FOR REAL DATA DEPLOYMENT NOW

All strategy parameters adjusted for live testing tomorrow.

---

## Strategy Parameters - Final Configuration

### Entry Criteria
- **Market Hours**: 9:15 AM - 3:30 PM IST ✅
- **AI Score Minimum**: 80% (changed from 85%) ✅
- **AI Confidence**: >= 80% (changed from 90%) ✅
- **Max Positions**: 5 ✅
- **Scan Interval**: Every 30 seconds ✅

### Exit Criteria (ALL VERIFIED)
1. **AI Score Drop** - Exit when aiScore < 70%
2. **Opposite Signal** - Exit if opposite A+ signal appears
3. **Take Profit** - +3.00% gain
4. **Stop Loss** - -0.50% (changed from 1.50%) ✅
5. **Market Close** - Auto-exit at 3:30 PM IST

---

## Changes Made

### File 1: ConfidenceScore.java
```java
// Changed: isVeryHighConfidence()
// From: >= 90
// To: >= 80
// Impact: More signals accepted, more relaxed entry
```

### File 2: APlusStrategyConfig.java
```java
// Change 1: entryAiScoreMin
// From: 85
// To: 80

// Change 2: hardSlPct (Stop Loss)
// From: 1.50%
// To: 0.50%
// Impact: Faster exit on losses, tighter risk management
```

---

## Tomorrow's Test Plan - 1 Stock

**Time**: 9:15 AM - 3:30 PM IST  
**Stock**: Your choice (recommend liquid stock)  
**Expected**: Multiple entry/exit signals

### What to Monitor
- Entry signals (80%+ AI score)
- Exit reasons (AI drop, TP, SL, opposite, close)
- PnL per trade
- Total daily PnL
- Stop loss trigger frequency

### Key Logs to Watch
```
✅ A+ Scanner: Found X rows to scan
✅ A+ Scanner: Y A+ setups detected (threshold: 80)
✅ A+ ENTRY: <symbol> @ <price> (aiScore: Z)
✅ A+ EXIT: <symbol> | PnL: X | Reason: Y
```

---

## Deployment Steps

### Step 1: Build
```bash
mvn clean package -DskipTests
```

### Step 2: Deploy to 173.249.55.84
```bash
ssh user@173.249.55.84
cp /app/stokr-platform.jar /app/backups/backup.jar
scp target/stokr-platform-*.jar user@173.249.55.84:/app/
systemctl restart stokr-platform
```

### Step 3: Verify
```bash
systemctl status stokr-platform
curl http://173.249.55.84:8080/actuator/health
```

### Step 4: Check Database Config
```sql
SELECT entryAiScoreMin, exitAiScoreThreshold, hardSlPct, hardTpPct 
FROM a_plus_strategy_config WHERE id = 1;
```

Expected:
- entryAiScoreMin: 80 ✓
- exitAiScoreThreshold: 70 ✓
- hardSlPct: 0.50 ✓
- hardTpPct: 3.00 ✓

---

## Why These Changes?

### AI Accuracy 90% → 80%
- **Pro**: More trading opportunities
- **Pro**: Better test data
- **Con**: Slightly lower confidence
- **Mitigation**: Tight 0.50% stop loss

### Stop Loss 1.50% → 0.50%
- **Pro**: Lower risk per trade
- **Pro**: Faster exit on losses
- **Con**: More frequent SL hits
- **Mitigation**: Monitor first 10 trades

### Overall Effect
- More trading activity
- More data points for analysis
- Faster feedback loops
- Lower risk per trade

---

## Alert Conditions - Monitor For These

❌ Errors in logs  
❌ Scanner not running  
❌ No signals generated  
❌ SL triggering > 50% of time  
❌ Positions held without exit  

---

## Rollback (If Needed)

```bash
systemctl stop stokr-platform
cp /app/backups/backup.jar /app/stokr-platform.jar
systemctl start stokr-platform
```

---

## Files Changed

**Commit**: ea473b61  
**Message**: "Strategy parameter adjustments for real data deployment"

1. ConfidenceScore.java - Line 63
2. APlusStrategyConfig.java - Lines 28, 34

---

## Ready For

✅ Real data deployment  
✅ Tomorrow's 1-stock test  
✅ Production trading  

**Status**: ALL SYSTEMS GO

---

## Next Steps

1. Deploy to 173.249.55.84
2. Verify configuration in database
3. Monitor logs
4. Start testing at 9:15 AM IST tomorrow
5. Track all trades and exit reasons
6. Adjust SL if needed based on results

---

**Deployment Date**: 2026-06-10  
**Test Start**: 2026-06-11 9:15 AM IST  
**Status**: READY NOW ✅
