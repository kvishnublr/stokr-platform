# Strategy Parameters Adjustment - Real Data Deployment

## User Request
- Deploy with REAL DATA (not mock)
- Test tomorrow with 1 stock
- Change AI accuracy from 90% to 80%
- Set Stop Loss (SL) to less than 70 basis points
- Verify all strategy conditions and exit criteria

---

## Current Configuration Analysis

### 1. AI Accuracy/Confidence Threshold

**Current State**:
```
ConfidenceScore.java (line 62-64):
  public boolean isVeryHighConfidence() {
      return confidenceScore != null && confidenceScore >= 90;  // ← 90%
  }

APlusStrategyConfig.java:
  entryAiScoreMin = 85  // Entry threshold is 85%
  exitAiScoreThreshold = 70  // Exit when drops below 70%
```

**What Needs to Change**:
- Change `isVeryHighConfidence()` from >= 90 to >= 80
- This makes AI accuracy requirement LESS strict (from 90% to 80%)
- Will allow more signals to trigger with lower confidence

---

### 2. Stop Loss (SL) Percentage

**Current State**:
```
APlusStrategyConfig.java (line 34):
  hardSlPct = BigDecimal.valueOf(1.50);  // ← 1.50% hard SL
  
AutomatedAPlusExitService.java (line 141-146):
  // Check SL: -1.5%
  if (pnlPct.compareTo(config.getHardSlPct().negate()) <= 0) {
      exitTrade(trade, "HARD_SL", currentPrice,
          String.format("Stop loss hit: %.2f%%", pnlPct));
```

**What Needs to Change**:
- Current: -1.50% (150 basis points)
- Requirement: Less than 70 basis points (less than 0.70%)
- New Value: **0.50%** (50 basis points) - TIGHTER stop loss
- This will exit positions faster if they go against you

---

## All Strategy Conditions & Exit Criteria

### Entry Conditions (AutomatedAPlusScannerService.java - Line 93)
```
✅ Market Hours: 9:15 AM - 3:30 PM IST
✅ AI Score >= 85% (or 80% after change)
✅ No existing position in same symbol
✅ Within max concurrent positions limit (currently 5)
```

### Exit Conditions (AutomatedAPlusExitService.java)
```
1. ✅ AI Score Drop: aiScore falls below 70%
2. ✅ Opposite Signal: Opposite A+ signal (score >= 85)
3. ✅ Hard Take Profit: +3.00% profit
4. ✅ Hard Stop Loss: -1.50% loss (WILL CHANGE TO 0.50%)
5. ✅ Market Close: Auto-exit at 3:30 PM IST
```

---

## Changes to Make

### Change 1: Lower AI Accuracy Threshold to 80%

**File**: `stokr-strategy/src/main/java/com/stokr/intraday/metrics/domain/ConfidenceScore.java`

**Current**:
```java
public boolean isVeryHighConfidence() {
    return confidenceScore != null && confidenceScore >= 90;
}
```

**Change To**:
```java
public boolean isVeryHighConfidence() {
    return confidenceScore != null && confidenceScore >= 80;  // Changed from 90 to 80
}
```

**Impact**: Signals with 80%+ confidence will now trigger (instead of requiring 90%+)

---

### Change 2: Lower Entry AI Score Minimum to 80%

**File**: `stokr-strategy/src/main/java/com/stokr/intraday/domain/APlusStrategyConfig.java`

**Current**:
```java
@Column(nullable = false)
private Integer entryAiScoreMin = 85;
```

**Change To**:
```java
@Column(nullable = false)
private Integer entryAiScoreMin = 80;  // Changed from 85 to 80
```

**Impact**: Entry signals requiring 80%+ AI score (instead of 85%+)

---

### Change 3: Reduce Stop Loss to 0.50% (Less than 70 bps)

**File**: `stokr-strategy/src/main/java/com/stokr/intraday/domain/APlusStrategyConfig.java`

**Current**:
```java
@Column(precision = 10, scale = 4, nullable = false)
private BigDecimal hardSlPct = BigDecimal.valueOf(1.50);  // 1.50% SL
```

**Change To**:
```java
@Column(precision = 10, scale = 4, nullable = false)
private BigDecimal hardSlPct = BigDecimal.valueOf(0.50);  // Changed from 1.50 to 0.50 (50 bps)
```

**Impact**: Positions exit FASTER if they drop 0.50% (instead of waiting for 1.50% drop)

---

## Summary of Changes

| Parameter | Current | New | Reason |
|-----------|---------|-----|--------|
| **AI Confidence Min** | 90% | 80% | More relaxed entry criteria |
| **Entry AI Score** | 85% | 80% | Align with confidence threshold |
| **Exit AI Score** | 70% | 70% | No change (keep as is) |
| **Take Profit** | +3.00% | +3.00% | No change |
| **Stop Loss** | -1.50% | -0.50% | Tighter SL (less risk per trade) |
| **Market Hours** | 9:15-3:30 PM IST | Same | No change |
| **Max Positions** | 5 | 5 | No change |

---

## Impact Analysis

### What This Means for Tomorrow's Test (1 Stock)

**More Aggressive Entry**:
- Will accept 80% confidence signals (instead of 90%)
- Will accept 80% AI score signals (instead of 85%)
- More signals will be generated
- Higher frequency of trades

**Tighter Risk Management**:
- Stop loss at 0.50% instead of 1.50%
- Less capital at risk per trade
- Faster exit on losses
- Smaller individual losses but more frequent

**Overall Effect**:
- More trading activity
- Lower risk per trade
- Faster exit decisions (both entry and stop)
- Better for real testing - more data points

---

## Testing Instructions for Tomorrow

### Step 1: Verify Configuration Before Start
```sql
SELECT entryAiScoreMin, exitAiScoreThreshold, hardSlPct, hardTpPct 
FROM a_plus_strategy_config 
WHERE id = 1;

Expected Result:
entryAiScoreMin: 80 (changed from 85)
exitAiScoreThreshold: 70 (unchanged)
hardSlPct: 0.50 (changed from 1.50)
hardTpPct: 3.00 (unchanged)
```

### Step 2: Test with 1 Stock
- Monitor AutomatedAPlusScannerService logs
- Verify entry signals at >= 80% AI score
- Verify stop loss triggers at -0.50%
- Verify take profit triggers at +3.00%
- Verify exit triggers when AI drops below 70%
- Verify opposite signal exit detection
- Verify market close auto-exit at 3:30 PM IST

### Step 3: Check Strategy Conditions
```
✅ Entry: aiScore >= 80% AND no existing position AND within 5 max positions
✅ Exit 1: aiScore drops below 70%
✅ Exit 2: Opposite signal appears with >= 85% (or 80%?) confidence
✅ Exit 3: P&L >= +3.00% (take profit)
✅ Exit 4: P&L <= -0.50% (stop loss)
✅ Exit 5: Market close at 3:30 PM IST
```

### Step 4: Monitor Logs
```
Look for:
✅ A+ Scanner: Found X total rows to scan
✅ A+ Scanner: Y A+ setups detected (threshold: 80)
✅ A+ ENTRY: <symbol> <qty> @ <price> (aiScore: Z)
✅ A+ EXIT: <symbol> @ <price> | PnL: XXX | Reason: YYY
❌ Any errors or exceptions
```

---

## Deployment Steps

### 1. Make Code Changes (3 files)
- ConfidenceScore.java - Change 90 to 80
- APlusStrategyConfig.java - Change entryAiScoreMin 85→80, hardSlPct 1.50→0.50

### 2. Database Migration (If Needed)
- If deploying to existing database, existing config stays at old values
- Either: run SQL UPDATE or reset to defaults
- New instances will use new defaults

### 3. Build & Deploy
```bash
mvn clean package -DskipTests
```

### 4. Restart Application
```bash
systemctl restart stokr-platform
```

### 5. Verify Configuration
```sql
SELECT * FROM a_plus_strategy_config WHERE id = 1;
```

---

## Risk Assessment

### Lower Risk Changes ✅
- AI accuracy 90% → 80% (more relaxed, more signals)
- Entry score 85% → 80% (aligns with confidence)

### Medium Risk Changes ⚠️
- Stop loss 1.50% → 0.50% (TIGHTER, exits faster)
  - Pro: Lower risk per trade
  - Con: More frequent exits, might exit too early on temporary dips
  - Mitigation: Monitor first trades carefully

### Recommendation
- Start with 1 stock as planned
- Monitor first 5-10 trades carefully
- Watch for early stop-loss exits
- Adjust SL if needed (might increase to 0.70% if SL triggers too often)

---

## Files to Change

```
1. ConfidenceScore.java
   Path: stokr-strategy/src/main/java/com/stokr/intraday/metrics/domain/
   Line: 63
   Change: >= 90 → >= 80

2. APlusStrategyConfig.java
   Path: stokr-strategy/src/main/java/com/stokr/intraday/domain/
   Line 28: entryAiScoreMin = 85 → 80
   Line 34: hardSlPct = 1.50 → 0.50
```

---

## Next Steps

1. ✅ Approve these parameter changes
2. ✅ Make the code changes (I can do this)
3. ✅ Build and deploy to production
4. ✅ Test with 1 stock tomorrow
5. ✅ Monitor logs and trade execution
6. ✅ Adjust SL if needed based on test results
7. ✅ Scale to more stocks after successful testing

---

## Summary

**Status**: Ready to make changes and deploy with real data

**Changes**: 
- AI accuracy: 90% → 80%
- Entry threshold: 85% → 80%
- Stop loss: 1.50% → 0.50%

**Ready for**: Real data deployment + tomorrow's 1-stock test

**Risk Level**: LOW for AI changes, MEDIUM for SL tightness (monitor closely)
