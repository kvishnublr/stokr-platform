-- STOKR PLATFORM - 6 DAY PERFORMANCE ANALYSIS
-- Run on Contabo server: psql -U postgres -h localhost -d stokr_platform -f PERFORMANCE_ANALYSIS.sql

\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║        STOKR STRATEGY PERFORMANCE - 6 DAY ANALYSIS               ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

-- Set date range to last 6 days
\set start_date 'now() - interval ''6 days'''
\set end_date 'now()'

\echo ''
\echo '=== SECTION 1: OVERALL STATISTICS (Last 6 Days) ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Total Signals",
  COUNT(CASE WHEN deleted = false THEN 1 END) as "Active Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  COUNT(CASE WHEN outcome_status = 'EXPIRED' THEN 1 END) as "Expired",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(AVG(confidence_score)::numeric, 2) as "Avg Confidence"
FROM strategy_signals
WHERE created_at >= (NOW() - interval '6 days')
  AND created_at <= NOW()
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 2: STRATEGY-WISE PERFORMANCE ==='
\echo ''

SELECT
  strategy_name as "Strategy",
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(AVG(realized_pnl)::numeric, 2) as "Avg PnL",
  ROUND(SUM(CASE WHEN realized_pnl > 0 THEN realized_pnl ELSE 0 END)::numeric, 2) as "Gross Wins",
  ROUND(SUM(CASE WHEN realized_pnl < 0 THEN realized_pnl ELSE 0 END)::numeric, 2) as "Gross Losses"
FROM strategy_signals
WHERE created_at >= (NOW() - interval '6 days')
  AND outcome_status IS NOT NULL
  AND deleted = false
GROUP BY strategy_name, DATE(created_at)
ORDER BY DATE(created_at) DESC, strategy_name;

\echo ''
\echo '=== SECTION 3: VWAP_MEAN_REVERSION ANALYSIS ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN signal_type = 'BUY' THEN 1 END) as "BUY Signals",
  COUNT(CASE WHEN signal_type = 'SELL' THEN 1 END) as "SELL Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(AVG(confidence_score)::numeric, 2) as "Avg Confidence",
  ROUND(SUM(CASE WHEN realized_pnl > 0 THEN realized_pnl ELSE 0 END)::numeric, 2) as "Total Wins"
FROM strategy_signals
WHERE strategy_name = 'VWAP_MEAN_REVERSION'
  AND created_at >= (NOW() - interval '6 days')
  AND deleted = false
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 4: MOMENTUM_BREAKOUT ANALYSIS ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN signal_type = 'BUY' THEN 1 END) as "BUY",
  COUNT(CASE WHEN signal_type = 'SELL' THEN 1 END) as "SELL",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(AVG(confidence_score)::numeric, 2) as "Avg Conf",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Total PnL"
FROM strategy_signals
WHERE strategy_name = 'MOMENTUM_BREAKOUT'
  AND created_at >= (NOW() - interval '6 days')
  AND deleted = false
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 5: EMA_TREND_FOLLOW ANALYSIS ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(AVG(realized_pnl)::numeric, 2) as "Avg PnL",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Daily PnL"
FROM strategy_signals
WHERE strategy_name = 'EMA_TREND_FOLLOW'
  AND created_at >= (NOW() - interval '6 days')
  AND deleted = false
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 6: NSE_SPIKE_DETECTION ANALYSIS ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Daily PnL"
FROM strategy_signals
WHERE strategy_name = 'NSE_SPIKE_DETECTION'
  AND created_at >= (NOW() - interval '6 days')
  AND deleted = false
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 7: MEAN_REVERSION STRATEGIES ANALYSIS ==='
\echo ''

SELECT
  strategy_name as "Strategy",
  DATE(created_at)::text as "Date",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Daily PnL"
FROM strategy_signals
WHERE strategy_name IN ('MEAN_REVERSION_RANGE_FADE', 'MEAN_REVERSION_V2')
  AND created_at >= (NOW() - interval '6 days')
  AND deleted = false
GROUP BY strategy_name, DATE(created_at)
ORDER BY DATE(created_at) DESC, strategy_name;

\echo ''
\echo '=== SECTION 8: QUALITY GATE EFFECTIVENESS ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  COUNT(*) as "Total Generated",
  COUNT(CASE WHEN deleted = false THEN 1 END) as "Persisted (After QG)",
  ROUND(100.0 * COUNT(CASE WHEN deleted = false THEN 1 END)::numeric / COUNT(*), 2) as "Pass Rate %"
FROM strategy_signals
WHERE created_at >= (NOW() - interval '6 days')
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '=== SECTION 9: BOTTOM PERFORMERS (Need Improvement) ==='
\echo ''

SELECT
  strategy_name as "Strategy",
  COUNT(*) as "Signals",
  COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END) as "Won",
  COUNT(CASE WHEN outcome_status = 'LOST' THEN 1 END) as "Lost",
  ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) as "Win Rate %",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Total PnL"
FROM strategy_signals
WHERE created_at >= (NOW() - interval '6 days')
  AND outcome_status IS NOT NULL
  AND deleted = false
GROUP BY strategy_name
HAVING COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END) >= 5
ORDER BY ROUND(100.0 * COUNT(CASE WHEN outcome_status = 'WON' THEN 1 END)::numeric /
        NULLIF(COUNT(CASE WHEN outcome_status IN ('WON', 'LOST') THEN 1 END), 0), 2) ASC;

\echo ''
\echo '=== SECTION 10: DAILY CUMULATIVE P&L ==='
\echo ''

SELECT
  DATE(created_at)::text as "Date",
  ROUND(SUM(realized_pnl)::numeric, 2) as "Daily PnL",
  COUNT(CASE WHEN realized_pnl > 0 THEN 1 END) as "Profitable Signals",
  COUNT(CASE WHEN realized_pnl < 0 THEN 1 END) as "Losing Signals",
  COUNT(CASE WHEN realized_pnl = 0 THEN 1 END) as "Breakeven"
FROM strategy_signals
WHERE created_at >= (NOW() - interval '6 days')
  AND outcome_status IS NOT NULL
  AND deleted = false
GROUP BY DATE(created_at)
ORDER BY DATE(created_at) DESC;

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║                     ANALYSIS COMPLETE                             ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'
