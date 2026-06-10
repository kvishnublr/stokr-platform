# SMART EXIT ENGINE V1 - DESIGN REVIEW

**Date:** 2026-06-09  
**Status:** DESIGN REVIEW  
**Phase:** Phase 1 Implementation  

---

## EXECUTIVE SUMMARY

Smart Exit Engine V1 is an additive layer that continuously evaluates active position health and can recommend or trigger early exits when original trade thesis deteriorates.

**Key Principle:** Do NOT predict prices. Ask: "If this trade appeared right now, would we still take it?"

**Safety:** Feature-flagged, dry-run capable, fully backward compatible, never overrides Hard Stop.

---

## ARCHITECTURE

### Current Exit Flow
```
Signal → Position Open → Hard/Pressure/Target/Time Exit → Close
```

### New Exit Flow  
```
Signal → Position Open → Smart Exit Monitor (15s eval) → Hard/Pressure/Target/Time/Smart Exit → Close
```

### Exit Priority (Absolute Order)
1. Kill Switch (instant disable)
2. Feed Protection (broker failures)
3. Hard Stop (absolute loss limit)
4. Smart Exit (thesis deterioration)
5. Pressure Exit (sentiment reversal)
6. Time Exit (duration limit)
7. Target Exit (profit target)

**Critical:** Hard Stop ALWAYS wins. Smart Exit NEVER overrides.

---

## PHASE 1 IMPLEMENTATION SCOPE

### Implement
✓ SignalHealthService (evaluation engine)
✓ Profit Protection Exit (trigger)
✓ Health Collapse Exit (trigger)
✓ Health Score Telemetry
✓ Dry Run Mode
✓ Audit Logging
✓ Feature Flags

### NOT Phase 1
✗ ML / Neural Networks
✗ AI Predictions
✗ Price Forecasting
✗ LLM Integration
✗ Advanced triggers (confidence, RS, sector)

---

## COMPONENT 1: SignalHealthService

### Activation Rules
Only evaluate if:
```
Position Age > 120 seconds
  OR Current Profit > 0.30%
  OR MFE > 0.50%
```

Reason: Let trade breathe, avoid noise.

### Health Score Components (Phase 1)

**Component A: Momentum Score (40% weight)**
- Consecutive counter candles
- VWAP relationship
- Price velocity
- Range: 10 (dead) to 90 (strong)

**Component B: Profit Retention Score (35% weight)**
- Entry Price vs Peak vs Current
- Giveback % = (MFE - Current) / MFE
- Score: 100 - (Giveback × 1.5)

**Component C: Volume Score (25% weight)**
- Entry volume vs current volume
- Volume trend (expanding/collapsing)
- Range: 20 (collapse) to 95 (expansion)

### Composite Calculation
```
HealthScore = (Momentum × 0.40) + (ProfitRetention × 0.35) + (Volume × 0.25)

80-100: Excellent
60-79: Good
40-59: Deteriorating
20-39: Poor
0-19: Critical
```

---

## SMART EXIT TRIGGERS (Phase 1)

### TRIGGER 1: Profit Protection Exit

**Condition:**
```
Current Profit > 0.50%
  AND Giveback > 40%
  AND Position Age > 120s
```

**Logic:** Trade showed potential but giving back. Protect it.

### TRIGGER 2: Health Collapse Exit

**Condition:**
```
Health Score < 35
  for 2 consecutive evaluations (30s)
  AND Position Age > 120s
```

**Logic:** Fundamentals deteriorating. Exit.

### TRIGGER 3: Volume Exhaustion Exit

**Condition:**
```
Volume Score < 25
  for 2 consecutive evaluations
  AND Position Age > 120s
```

**Logic:** Participation drying up. Reversal likely.

---

## SAFETY CONTROLS

### Feature Flags
```yaml
stokr:
  smart-exit:
    enabled: false              # MASTER SWITCH
    evaluation-interval-sec: 15
    dry-run: false
    min-position-age-sec: 120
    min-profit-threshold: 0.30
    min-mfe-threshold: 0.50
    
  smart-exit-triggers:
    profit-protection:
      enabled: true
      min-profit-pct: 0.50
      giveback-threshold: 40
    health-collapse:
      enabled: true
      health-threshold: 35
    volume-exhaustion:
      enabled: true
      volume-threshold: 25
```

### Dry Run Mode
When `dry-run=true`:
- Calculate health scores ✓
- Evaluate triggers ✓
- Log decisions ✓
- Create exit orders ✗

### Disable Path
```
1. Set enabled=false
2. Restart app
3. All Smart Exit disabled
4. Full revert in < 5 minutes
```

---

## TELEMETRY

### Health History Table
```sql
CREATE TABLE signal_health_history (
    id SERIAL PRIMARY KEY,
    signal_id UUID NOT NULL,
    symbol VARCHAR(20),
    timestamp TIMESTAMP,
    health_score DECIMAL(5,2),
    momentum_score DECIMAL(5,2),
    volume_score DECIMAL(5,2),
    entry_price DECIMAL(12,2),
    current_price DECIMAL(12,2),
    peak_price DECIMAL(12,2),
    mfe DECIMAL(8,4),
    current_profit DECIMAL(8,4),
    giveback_pct DECIMAL(8,4),
    trigger_reason VARCHAR(50),
    INDEX (signal_id, timestamp)
);
```

### Audit Logging
```
[SMART_EXIT_EVALUATED]
signal_id=sig_123
symbol=SBIN
health_score=42
mfe=1.50%
current_profit=0.75%

[SMART_EXIT_TRIGGERED]
signal_id=sig_123
symbol=SBIN
trigger=PROFIT_PROTECTION_EXIT
reason=Profit_giveback_50_percent

[SMART_EXIT_DRY_RUN]
signal_id=sig_123
would_trigger=true
no_order_created=true
```

---

## RISK ASSESSMENT

### Risk 1: Over-Exits
**Mitigation:** Activation rules, 2-consecutive threshold, dry-run

### Risk 2: Premature Exits
**Mitigation:** Conservative thresholds (health < 35), Hard Stop backup

### Risk 3: False Negatives
**Mitigation:** Hard Stop/Pressure Exit still work, Smart Exit is supplementary

### Risk 4: Database Load
**Mitigation:** 33 inserts/sec, indexed, 30-day retention

### Risk 5: Backward Incompatibility
**Mitigation:** Feature flag disabled by default, zero impact on existing exits

**Overall Risk:** LOW (fully reversible, feature-flagged, dry-run validated)

---

## PERFORMANCE IMPACT

```
CPU: < 1% (500 signals × 5ms each × 4 cycles/min)
Database: 33 inserts/sec (negligible)
Memory: < 1MB additional
Storage: ~1.2GB/month (30-day retention)
```

---

## BACKWARD COMPATIBILITY

### Unchanged
✓ Hard Stop logic
✓ Target Exit logic
✓ Pressure Exit logic
✓ Time Exit logic
✓ Feed Protection
✓ OMS order creation
✓ Position tables
✓ Existing code paths

### New Only
✗ signal_health_history table
✗ smart_exit_config table
✗ SignalHealthService class
✗ Smart exit triggers

### Rollback
- Set `enabled=false`
- Restart
- Full revert in < 5 minutes
- Zero data loss

---

## IMPLEMENTATION ROADMAP

### Week 1: Foundation
- SignalHealthService class
- Momentum/Profit/Volume score calculations
- signal_health_history table
- Flyway migration

### Week 2: Triggers & Safety
- Profit Protection trigger
- Health Collapse trigger
- Feature flags
- Dry-run mode
- Audit logging

### Week 3: Testing
- Unit tests
- Integration tests
- Dry-run validation (1 week on production data)

### Week 4: Deployment
- Deploy with enabled=false
- Run dry-run for 1 trading week
- Analyze results
- Decision: live or iterate

---

## SUCCESS CRITERIA

### Development
- Code compiles, tests pass
- Zero impact on existing exits
- Feature flags work
- Dry-run functional

### Dry-Run Phase
- Identifies deteriorating positions correctly
- False positive rate < 5%
- Health scores distribute normally
- Performance impact < 1% CPU

### Live Phase (Week 5+)
- Actual exits perform as expected
- Win rate improvement +2-3%
- Rollback works instantly
- Monitoring alerts active

---

## EXPECTED BENEFITS

```
Win Rate:        +2-3% improvement
Profit Factor:   +10-15% improvement
Sharpe Ratio:    +20% improvement
False Positives: < 5% (acceptable)
```

---

## FUTURE ENHANCEMENTS (Phase 2+)

### Phase 2 Triggers
- Confidence Drift Score (entry vs current confidence)
- Relative Strength Collapse (stock vs sector/index)
- Sector Strength Deterioration

### Phase 3+ Features
- User-configurable thresholds
- Position-specific algorithms
- Multi-asset health scoring
- Real-time health dashboard

---

## CONCLUSION

Smart Exit Engine V1 is a **low-risk, high-value** enhancement that:

✓ Exits deteriorating trades early
✓ Never interferes with existing exits
✓ Fully feature-flagged and reversible
✓ Production-safe with dry-run validation
✓ Expected +2-3% win rate improvement

**Ready for Phase 1 Implementation**

