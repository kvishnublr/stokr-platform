# HISTORICAL DATA AVAILABILITY - FINAL CHECK
## What Exists in Codebase & Schema

Date: 2026-06-09
Method: Codebase inspection only (no database access)
Status: CONFIRMED - All necessary data structures exist

---

## KEY FINDINGS

### ✅ Data Schema Confirmed

**Migration V33__signal_outcome_tracking.sql exists and contains:**

All required outcome fields are stored in `strategy_signals` table:

```sql
outcome_status          VARCHAR(32)      -- PENDING, CLOSED, EXPIRED
outcome_time            TIMESTAMPTZ      -- When trade closed
entry_price             NUMERIC(24, 8)   -- Entry price
exit_price              NUMERIC(24, 8)   -- Exit price
realized_pnl            NUMERIC(24, 8)   -- P&L amount
unrealized_pnl          NUMERIC(24, 8)   -- Current P&L
max_favorable_excursion NUMERIC(24, 8)   -- MFE (peak profit)
max_adverse_excursion   NUMERIC(24, 8)   -- MAE (worst drawdown)
hit_target              BOOLEAN          -- Target hit?
hit_stoploss            BOOLEAN          -- Stop loss hit?
risk_reward_achieved    NUMERIC(10, 4)   -- RR ratio
```

### ✅ Additional Outcome Data

**Also in strategy_signals table (V2 migration):**
- confidence_score (DECIMAL)
- rsi_value (DECIMAL)
- vwap_distance (DECIMAL)
- market_regime (VARCHAR)
- probability (DECIMAL)
- trade_quality (VARCHAR)

### ✅ Exit Telemetry Table

**strategy_exit_telemetry table exists with:**
- signal_id (FK to strategy_signals)
- entry_time, exit_time
- unrealized_pnl_peak (MFE)
- unrealized_pnl_trough (MAE)
- exit_category (TARGET, STOP, PRESSURE, TIME, etc.)
- exit_reason (text description)
- hold_seconds (trade duration)

### ✅ API Endpoints Ready

**Scripts confirm API access to outcome data:**

```
/api/admin/signals/stats       -- Aggregate statistics
/api/admin/signals             -- Signal list with outcomes
```

Scripts verify_outcomes.py can retrieve:
- outcomeStatus
- realizedPnl
- unrealizedPnl
- strategyName
- symbol

### ✅ Historical Data Infrastructure

**Files found:**
- docs/backtest-results-2026-05-26.md (historical backtest report)
- scripts/verify_outcomes.py (data verification script)
- scripts/run_active_strategy_backtests.py (backtest runner)
- Migration V103 (outcome comment tracking)

---

## DATA AVAILABILITY SUMMARY

| Component | Status | Evidence |
|-----------|--------|----------|
| **Entry metrics stored** | ✅ YES | confidence_score, rsi_value, vwap_distance, market_regime, probability, trade_quality in strategy_signals |
| **Outcome data stored** | ✅ YES | outcome_status, entry_price, exit_price, realized_pnl, mfe, mae in strategy_signals |
| **Exit telemetry stored** | ✅ YES | strategy_exit_telemetry table with complete trade lifecycle |
| **Join capability** | ✅ YES | signal_id foreign key enables JOIN between signals and telemetry |
| **Historical retention** | ✅ 90 DAYS | application.yml shows 90-day retention for metrics |
| **API access** | ✅ YES | /api/admin/signals endpoints available |
| **Query indexes** | ✅ YES | idx_signals_outcome_live, idx_signals_created_live for fast queries |

---

## BACKTEST FEASIBILITY

### Can we build the backtest query?

**YES - Complete query is possible:**

```sql
SELECT 
  DATE(s.created_at) as trade_date,
  s.confidence_score,
  s.rsi_value,
  s.probability,
  s.vwap_distance,
  s.market_regime,
  s.trade_quality,
  s.entry_price,
  s.exit_price,
  s.realized_pnl,
  s.max_favorable_excursion as mfe,
  s.max_adverse_excursion as mae,
  e.exit_category,
  e.hold_seconds,
  CASE 
    WHEN s.realized_pnl > 0 THEN 'WIN'
    WHEN s.realized_pnl = 0 THEN 'BREAK_EVEN'
    ELSE 'LOSS'
  END as result
FROM strategy_signals s
LEFT JOIN strategy_exit_telemetry e ON s.id = e.signal_id
WHERE s.deleted = FALSE
  AND s.test_trade = FALSE
  AND s.backtest_run_id IS NULL
  AND s.outcome_status = 'CLOSED'
ORDER BY s.created_at DESC;
```

### What we can calculate:

- ✅ Win rate by market_regime
- ✅ Average PnL by rsi_value bracket
- ✅ Profit factor by vwap_distance range
- ✅ Trade quality distribution
- ✅ Confidence score separation (winners vs losers)
- ✅ Top 5 metric predictors
- ✅ Optimal entry filter thresholds

---

## ONLY MISSING: ACTUAL TRADE DATA

### What we have:
- ✅ Schema to store trade outcomes
- ✅ API endpoints to retrieve outcomes
- ✅ Scripts to query outcomes
- ✅ Database infrastructure (90-day retention)
- ✅ Indexes for fast queries
- ✅ Backtest infrastructure

### What we DON'T know:
- ❓ Whether > 100 completed trades exist in database
- ❓ Date range of available trades
- ❓ Whether database is populated with live trading data

---

## RECOMMENDATION

### ✅ OPTION B: Data Exists But Needs Extraction

**Infrastructure is 100% ready for backtest.**

**Only requirement:** Run these queries on production server (173.249.55.84):

```sql
-- Query 1: Sample size verification
SELECT 
  COUNT(*) as total_completed_trades,
  COUNT(DISTINCT DATE(created_at)) as trading_days,
  MIN(DATE(created_at)) as first_trade_date,
  MAX(DATE(created_at)) as last_trade_date
FROM strategy_signals
WHERE deleted = FALSE
  AND test_trade = FALSE
  AND backtest_run_id IS NULL
  AND outcome_status = 'CLOSED';

-- Query 2: If sample > 100, run full backtest query above
```

**Once sample size is confirmed:**

If total >= 100 trades:
- Extract data via script/API
- Import into analysis environment
- Run correlation analysis
- Identify top 5 predictors
- Calculate optimal thresholds
- **Generate ENTRY_FILTER_BACKTEST.md with actual numbers**

If total < 100 trades:
- **WAIT** 3-7 more trading days
- Re-check sample size
- Then proceed with backtest

---

## STATUS

✅ **Infrastructure exists and is ready**

⚠️ **Sample size unknown (need to verify on production)**

❌ **Cannot proceed with full backtest without sample size verification**

**Next action:** Query production database for trade count

