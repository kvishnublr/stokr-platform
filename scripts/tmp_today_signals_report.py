#!/usr/bin/env python3
"""Today's signal analysis report from prod DB."""
import json
import paramiko
import urllib.request
from collections import defaultdict

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read() + e.read()).decode("utf-8", "replace")


SQL = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on

SELECT 'SIGNALS' as section;
SELECT
  strategy_name,
  COALESCE(outcome_status, 'PENDING') as outcome,
  pipeline,
  signal_type,
  count(*)::text as cnt,
  round(sum(COALESCE(realized_pnl, 0))::numeric, 2)::text as sum_realized,
  round(avg(COALESCE(realized_pnl, 0))::numeric, 2)::text as avg_realized,
  round(sum(COALESCE(unrealized_pnl, 0))::numeric, 2)::text as sum_unrealized
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
  AND is_test_trade = false
  AND backtest_run_id IS NULL
GROUP BY strategy_name, outcome_status, pipeline, signal_type
ORDER BY strategy_name, outcome, pipeline;

SELECT 'SIGNAL_DETAIL' as section;
SELECT
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_time,
  strategy_name,
  symbol,
  signal_type,
  pipeline,
  COALESCE(outcome_status, 'PENDING'),
  round(COALESCE(confidence_score, 0)::numeric, 1)::text,
  round(COALESCE(entry_reference_price, 0)::numeric, 2)::text,
  round(COALESCE(stop_price, 0)::numeric, 2)::text,
  round(COALESCE(target_price, 0)::numeric, 2)::text,
  round(COALESCE(realized_pnl, 0)::numeric, 2)::text,
  COALESCE(hit_target::text, 'f'),
  COALESCE(hit_stoploss::text, 'f'),
  id::text
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false AND backtest_run_id IS NULL
ORDER BY created_at;

SELECT 'OMS_BY_SIGNAL' as section;
SELECT
  s.strategy_name,
  s.symbol,
  to_char(s.created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS'),
  o.execution_mode::text,
  o.state::text,
  o.side,
  COALESCE(o.broker_external_order_id, ''),
  COALESCE(o.reject_reason, '')
FROM strategy_signals s
JOIN oms_orders o ON o.signal_id = s.id AND o.deleted = false
WHERE s.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND s.deleted = false AND s.is_test_trade = false
ORDER BY s.created_at, o.execution_mode;

SELECT 'OMS_SUMMARY' as section;
SELECT
  COALESCE(o.strategy_key, s.strategy_name) as strategy,
  o.execution_mode::text,
  o.state::text,
  count(*)::text
FROM oms_orders o
LEFT JOIN strategy_signals s ON s.id = o.signal_id
WHERE o.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND o.deleted = false
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

SELECT 'STRATEGY_TOTALS' as section;
SELECT
  strategy_name,
  count(*)::text as total_signals,
  count(*) FILTER (WHERE outcome_status = 'TARGET_HIT')::text as target_hit,
  count(*) FILTER (WHERE outcome_status IN ('SL_HIT','STOPLOSS_HIT'))::text as sl_hit,
  count(*) FILTER (WHERE outcome_status = 'PRESSURE_EXIT')::text as pressure_exit,
  count(*) FILTER (WHERE outcome_status = 'FEED_PROTECTION')::text as feed_protection,
  count(*) FILTER (WHERE outcome_status = 'MANUAL')::text as manual,
  count(*) FILTER (WHERE outcome_status IN ('RUNNING','PENDING') OR outcome_status IS NULL)::text as still_open,
  count(*) FILTER (WHERE outcome_status = 'TIME_EXIT')::text as time_exit,
  count(*) FILTER (WHERE outcome_status = 'BREAKEVEN_EXIT')::text as breakeven,
  round(sum(COALESCE(realized_pnl,0))::numeric, 2)::text as total_pnl,
  round(avg(COALESCE(confidence_score,0))::numeric, 1)::text as avg_conf
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false AND backtest_run_id IS NULL
GROUP BY strategy_name
ORDER BY total_signals DESC;
"""

out = run(
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
    + json.dumps(SQL)
)
print(out)
c.close()
