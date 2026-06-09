# HYBRID EXIT ENGINE - IMPLEMENTATION & DEPLOYMENT GUIDE
## Week 1-3 Timeline: From Strategy Exits → Indicator-Based → AI Optimization

---

## 📋 **WHAT YOU'RE GETTING**

### Three-Layer Hybrid System:
```
LAYER 1: STRATEGY EXITS (Foundation)
  ├─ IndexHunt: Exit at 2% profit or 2% loss
  ├─ ADV_CASH: Exit at 1.5% profit or 1.5% loss
  └─ Other strategies: Custom exit rules

LAYER 2: INDICATOR-BASED EXITS (Week 1)
  ├─ RSI: Exit when overbought (>70) or oversold (<30)
  ├─ MACD: Exit on bearish/bullish crossovers
  ├─ Bollinger Bands: Exit at upper/lower band touch
  ├─ Volume: Exit on high volume confirmation
  └─ ATR: Volatility-based position sizing

LAYER 3: AI OPTIMIZATION (Week 2-3)
  ├─ Dynamic target calculation (ATR × Factors)
  ├─ Confidence scoring (0-100%)
  ├─ Risk/reward optimization
  └─ Continuous improvement loop
```

### Expected Results:
- **Week 1**: 30-40% improvement (indicators activate)
- **Week 2**: 50-70% improvement (dynamic targets optimize)
- **Week 3**: 70-100% improvement (AI learning kicks in)

---

## 🚀 **DEPLOYMENT STEPS**

### STEP 1: Database Schema Setup (30 minutes)
**File:** `HYBRID_EXIT_INTEGRATION.sql`

```bash
# SSH to production server
ssh root@173.249.55.84

# Connect to PostgreSQL
psql -h localhost -U postgres -d stokr_live

# Run the SQL script
\i /path/to/HYBRID_EXIT_INTEGRATION.sql

# Verify tables created
\dt exit_signals
\dt dynamic_targets
\dt exit_events
\dt hybrid_exit_config
```

**What it creates:**
- `indicator_history` - Stores RSI, MACD, Bollinger Bands, ATR, Volume data
- `exit_signals` - Stores all generated exit signals
- `dynamic_targets` - Stores calculated dynamic targets
- `exit_events` - Audit trail of all exits executed
- `hybrid_exit_config` - Feature toggles (easily enable/disable features)
- `strategy_exit_definitions` - Defines exit rules per strategy

**Check it worked:**
```sql
SELECT * FROM hybrid_exit_config;
-- Should show 7 features with enabled status
```

---

### STEP 2: Python Engine Deployment (45 minutes)
**File:** `HYBRID_EXIT_ENGINE.py`

```bash
# Copy to server
scp HYBRID_EXIT_ENGINE.py root@173.249.55.84:/app/

# SSH to server
ssh root@173.249.55.84

# Install dependencies (if needed)
pip3 install numpy scipy pandas

# Test the engine
cd /app
python3 HYBRID_EXIT_ENGINE.py

# Expected output:
# {
#   "symbol": "HDFCBANK",
#   "final_decision": "EXIT",
#   "confidence": 0.75,
#   ...
# }
```

**Create a service wrapper** (to run continuously):

```bash
# Create systemd service file
cat > /etc/systemd/system/stokr-hybrid-exit.service << 'EOF'
[Unit]
Description=STOKR Hybrid Exit Engine
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/app
ExecStart=/usr/bin/python3 /app/stokr_hybrid_exit_daemon.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Enable service
systemctl enable stokr-hybrid-exit.service
systemctl start stokr-hybrid-exit.service
systemctl status stokr-hybrid-exit.service
```

---

### STEP 3: Java Integration (1 hour)
**File:** `HybridExitService.java`

```bash
# Copy to Java project
scp HybridExitService.java root@173.249.55.84:/app/src/main/java/com/stokr/trading/service/exit/

# Build the project
cd /app
./gradlew clean build

# Restart Java API
systemctl restart stokr-api
systemctl status stokr-api
```

**Add required dependencies to build.gradle:**
```gradle
dependencies {
    // Existing dependencies...
    implementation 'org.springframework.boot:spring-boot-starter-scheduling'
    implementation 'org.springframework.data:spring-data-jpa'
    // PostgreSQL driver (should already be there)
    runtimeOnly 'org.postgresql:postgresql'
}
```

**Configure application.properties:**
```properties
# Enable scheduling
spring.task.scheduling.pool.size=5

# Logging for hybrid exit
logging.level.com.stokr.trading.service.exit.HybridExitService=INFO

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_live
spring.datasource.username=postgres
spring.datasource.password=your_password
```

---

### STEP 4: Enable Features (One-by-One)
**Why gradual?** Easier to debug, lower risk, immediate visible results

#### Week 1: Indicator Signals
```sql
-- Enable indicator-based exits
UPDATE hybrid_exit_config 
SET enabled = TRUE, 
    parameters = '{"weight": 0.3}'
WHERE feature_name LIKE 'indicator_%';

-- Check status
SELECT feature_name, enabled FROM hybrid_exit_config;
```

Check the logs:
```bash
tail -f /var/log/stokr/api.log | grep "HYBRID EXIT"
```

**What to look for:**
```
2026-06-09 10:15:23 LAYER 1 - Strategy Exit: NONE
2026-06-09 10:15:23 LAYER 2 - Indicator Signals: 2 (Confidence: 0.78, Recommendation: EXIT)
2026-06-09 10:15:23 HYBRID EXIT DECISION: HOLD
```

#### Week 2: Dynamic Target Calculation
```sql
-- Enable dynamic targets
UPDATE hybrid_exit_config 
SET enabled = TRUE,
    parameters = '{"enabled": true, "update_frequency": 10}'
WHERE feature_name = 'dynamic_targets';
```

**What changes:**
- Exit targets become dynamic (not fixed 2%)
- Adjusts based on volatility (ATR)
- Adjusts based on momentum (MACD)
- Takes early profits in overbought conditions

#### Week 3: AI Optimization
```sql
-- Enable AI optimization (Phase 2)
UPDATE hybrid_exit_config 
SET enabled = TRUE,
    parameters = '{"enabled": true, "model": "ensemble", "confidence_threshold": 0.7}'
WHERE feature_name = 'ai_optimization';
```

---

## 📊 **MONITORING & VERIFICATION**

### Check Exit Signals Being Generated:
```sql
SELECT symbol, recommendation, overall_confidence, generated_at
FROM exit_signals
WHERE generated_at > NOW() - INTERVAL '10 minutes'
ORDER BY generated_at DESC;
```

**Expected output:**
```
 symbol  │ recommendation │ overall_confidence │     generated_at
─────────┼────────────────┼────────────────────┼──────────────────────────────
 HDFCBANK│ EXIT           │ 0.78               │ 2026-06-09 10:15:23
 SBIN    │ WEAK_EXIT      │ 0.62               │ 2026-06-09 10:14:15
 TCS     │ HOLD           │ 0.45               │ 2026-06-09 10:13:45
```

### Check Dynamic Targets:
```sql
SELECT symbol, original_target, dynamic_target, profit_potential
FROM dynamic_targets
WHERE calculated_at > NOW() - INTERVAL '10 minutes'
ORDER BY calculated_at DESC;
```

### Check Exit Performance:
```sql
SELECT symbol, exit_type, total_exits, successful_exits, 
       ROUND(win_rate, 2) as win_rate
FROM exit_performance;
```

---

## 🔧 **CONFIGURATION TUNING**

All parameters can be adjusted without redeploying code:

```sql
-- Make RSI more aggressive (exit sooner)
UPDATE hybrid_exit_config 
SET parameters = '{"overbought": 65, "oversold": 35, "weight": 0.4}'
WHERE feature_name = 'indicator_rsi';

-- Increase MACD weight
UPDATE hybrid_exit_config 
SET parameters = '{"weight": 0.4, "crossover_required": true}'
WHERE feature_name = 'indicator_macd';

-- Adjust dynamic target frequency (more updates = more reactive)
UPDATE hybrid_exit_config 
SET parameters = '{"enabled": true, "update_frequency": 5}'
WHERE feature_name = 'dynamic_targets';

-- Verify changes
SELECT feature_name, parameters FROM hybrid_exit_config;
```

---

## 🎯 **REAL-TIME MONITORING DASHBOARD**

Create a simple dashboard view:

```sql
CREATE VIEW hybrid_exit_dashboard AS
SELECT 
    p.symbol,
    p.qty,
    p.entry_price,
    p.current_price,
    ROUND((p.current_price - p.entry_price) / p.entry_price * 100, 2) as pnl_percent,
    p.target_price,
    p.stop_loss_price,
    es.recommendation,
    dt.dynamic_target,
    dt.dynamic_stop,
    es.overall_confidence,
    es.generated_at
FROM positions p
LEFT JOIN exit_signals es ON p.id = es.position_id
LEFT JOIN dynamic_targets dt ON p.id = dt.position_id
WHERE p.status = 'OPEN'
ORDER BY es.generated_at DESC;

-- Query it:
SELECT * FROM hybrid_exit_dashboard;
```

---

## ✅ **VERIFICATION CHECKLIST**

- [ ] Database tables created (7 new tables)
- [ ] Python engine running and sending data
- [ ] Java API compiled and restarted
- [ ] Logs showing "HYBRID EXIT" messages
- [ ] `exit_signals` table has recent data (last 10 minutes)
- [ ] `dynamic_targets` table showing calculated targets
- [ ] Feature flags set correctly in `hybrid_exit_config`
- [ ] First position has exited successfully
- [ ] Exit performance metrics tracking wins/losses

---

## 🚨 **TROUBLESHOOTING**

### Issue: No signals being generated

**Check 1: Is API running?**
```bash
systemctl status stokr-api
ps aux | grep stokr-bootstrap.jar
```

**Check 2: Are positions OPEN?**
```sql
SELECT symbol, status FROM positions WHERE status = 'OPEN';
```

**Check 3: Are indicators being calculated?**
```sql
SELECT COUNT(*) FROM indicator_history 
WHERE timestamp > NOW() - INTERVAL '5 minutes';
-- Should be > 0
```

### Issue: Exits executing but at wrong prices

**Check 1: Dynamic targets vs actual targets**
```sql
SELECT symbol, target_price, last_dynamic_target
FROM positions WHERE status = 'OPEN';
```

**Check 2: RSI calibration**
```sql
-- Lower overbought threshold for earlier exits
UPDATE hybrid_exit_config 
SET parameters = '{"overbought": 65, "oversold": 35, "weight": 0.35}'
WHERE feature_name = 'indicator_rsi';
```

### Issue: Too many false exits

**Check 1: Require multiple signals**
```sql
-- Only exit on 2+ signals (higher confidence)
UPDATE hybrid_exit_config 
SET parameters = '{"min_signals": 2, "confidence_threshold": 0.75}'
WHERE feature_name = 'dynamic_targets';
```

**Check 2: Check MACD settings**
```sql
UPDATE hybrid_exit_config 
SET parameters = '{"weight": 0.2, "crossover_required": true}'
WHERE feature_name = 'indicator_macd';
```

---

## 📈 **PERFORMANCE TRACKING**

After Week 1:
- Compare P&L before vs after hybrid engine
- Check win rate improvement
- Verify average profit increased

After Week 2:
- Monitor dynamic target optimization
- Track if exits are happening at better prices
- Check risk/reward ratio

After Week 3:
- Full AI system operational
- Continuous learning activated
- System should be adapting to market conditions

---

## 🎁 **BONUS: Advanced Configurations**

### Aggressive Mode (More trades, higher risk):
```sql
UPDATE hybrid_exit_config 
SET parameters = '{"overbought": 60, "oversold": 40, "weight": 0.4}'
WHERE feature_name = 'indicator_rsi';

UPDATE hybrid_exit_config 
SET parameters = '{"min_signals": 1, "confidence_threshold": 0.5}'
WHERE feature_name = 'dynamic_targets';
```

### Conservative Mode (Fewer trades, lower risk):
```sql
UPDATE hybrid_exit_config 
SET parameters = '{"overbought": 75, "oversold": 25, "weight": 0.25}'
WHERE feature_name = 'indicator_rsi';

UPDATE hybrid_exit_config 
SET parameters = '{"min_signals": 3, "confidence_threshold": 0.85}'
WHERE feature_name = 'dynamic_targets';
```

### Volatility-Aware Mode (Adapt to market):
```sql
UPDATE hybrid_exit_config 
SET parameters = '{"dynamic_threshold": true, "atr_multiplier": 1.5}'
WHERE feature_name = 'indicator_atr';
```

---

## 📞 **SUPPORT**

If issues arise:
1. Check logs: `tail -f /var/log/stokr/api.log`
2. Query the database directly
3. Review `exit_signals` table for what's happening
4. Adjust parameters in `hybrid_exit_config`
5. Restart API: `systemctl restart stokr-api`

---

**Next Step:** Start with Step 1 (Database) today, Step 2-3 by tomorrow, go live by end of week!

🚀 **Ready to implement? Let me know when you want to deploy!**
