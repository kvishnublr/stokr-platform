#!/usr/bin/env python3
"""One-shot forensic audit queries against production. Read-only."""
import paramiko
import sys

HOST = "173.249.55.84"
USER = "root"
PASS = "Temp1234.."

SQL = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on

\echo ===IST_NOW===
SELECT to_char(now() AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI:SS TZ') as ist_now;

\echo ===STRATEGY_SIGNALS_SCHEMA===
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'strategy_signals'
ORDER BY ordinal_position;

\echo ===OMS_ORDERS_SCHEMA===
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'oms_orders'
ORDER BY ordinal_position;

\echo ===POSITION_TABLES===
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name ILIKE '%position%'
ORDER BY table_name;

\echo ===ALL_SIGNALS_TODAY_FULL===
SELECT
  id,
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_created,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_updated,
  strategy_name, symbol, signal_type, pipeline,
  COALESCE(outcome_status, 'NULL') as outcome,
  COALESCE(status::text, 'NULL') as status,
  round(COALESCE(entry_price,0)::numeric,2) as entry_px,
  round(COALESCE(exit_price,0)::numeric,2) as exit_px,
  round(COALESCE(target_price,0)::numeric,2) as target_px,
  round(COALESCE(stoploss_price,0)::numeric,2) as sl_px,
  round(COALESCE(realized_pnl,0)::numeric,2) as pnl,
  COALESCE(hit_target::text,'f') as hit_tgt,
  COALESCE(hit_stoploss::text,'f') as hit_sl,
  COALESCE(suppressed::text,'f') as suppressed,
  COALESCE(manual_exit_suppressed::text,'f') as manual_exit_suppressed,
  COALESCE(closed_by::text,'NULL') as closed_by,
  COALESCE(closed_reason::text,'NULL') as closed_reason
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false AND backtest_run_id IS NULL
ORDER BY created_at;

\echo ===RUNNING_OR_OPEN_SIGNALS===
SELECT id, strategy_name, symbol, outcome_status, status, created_at
FROM strategy_signals
WHERE deleted = false AND is_test_trade = false
  AND (outcome_status IN ('RUNNING','PENDING') OR outcome_status IS NULL
       OR status::text IN ('ACTIVE','RUNNING','OPEN'))
ORDER BY created_at DESC LIMIT 50;

\echo ===MANUAL_OUTCOME_SIGNALS_TODAY===
SELECT id, strategy_name, symbol, outcome_status, closed_by, closed_reason,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_updated
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
  AND (outcome_status = 'MANUAL' OR closed_by ILIKE '%USER%' OR closed_reason ILIKE '%manual%'
       OR closed_reason ILIKE '%MANUAL%' OR closed_reason ILIKE '%CLOSED_BY_USER%');

\echo ===MANUAL_EXIT_SUPPRESSION_TABLE===
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND (
  table_name ILIKE '%suppress%' OR table_name ILIKE '%manual%exit%'
) ORDER BY table_name;

\echo ===SUPPRESSION_RECORDS_TODAY===
SELECT * FROM signal_manual_exit_suppression
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
ORDER BY created_at
LIMIT 100;

\echo ===ALL_OMS_TODAY===
SELECT
  id,
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_upd,
  COALESCE(strategy_key, 'NO_KEY') as strategy,
  symbol, side, execution_mode::text as mode, state::text as state,
  COALESCE(reject_reason, 'none') as reject_reason,
  COALESCE(broker_external_order_id, 'none') as broker_id,
  signal_id,
  quantity, filled_quantity
FROM oms_orders
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY created_at;

\echo ===OMS_REJECT_GROUPED===
SELECT execution_mode::text, COALESCE(reject_reason, 'NULL') as reason, count(*) as cnt
FROM oms_orders
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND state::text = 'REJECTED'
GROUP BY 1, 2 ORDER BY cnt DESC;

\echo ===PORTFOLIO_POSITIONS_OPEN===
SELECT column_name FROM information_schema.columns
WHERE table_name = 'portfolio_positions' ORDER BY ordinal_position;

\echo ===PORTFOLIO_POSITIONS_ALL===
SELECT * FROM portfolio_positions WHERE deleted = false ORDER BY updated_at DESC LIMIT 50;

\echo ===RECONCILIATION_TABLES===
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name ILIKE '%reconcil%'
ORDER BY table_name;

\echo ===RECONCILIATION_EVENTS_TODAY===
SELECT
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  event_type, symbol, details
FROM reconciliation_events
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
ORDER BY created_at
LIMIT 200;

\echo ===TRADER_TERMINAL_TABLES===
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND (
  table_name ILIKE '%trader%' OR table_name ILIKE '%terminal%'
) ORDER BY table_name;

\echo ===SIGNALS_AROUND_3PM===
SELECT
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_c,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_u,
  strategy_name, symbol, outcome_status, closed_by, closed_reason, realized_pnl
FROM strategy_signals
WHERE updated_at >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND updated_at < ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY updated_at;

\echo ===OMS_AROUND_3PM===
SELECT
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  strategy_key, symbol, side, execution_mode::text, state::text,
  reject_reason, broker_external_order_id
FROM oms_orders
WHERE created_at >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND created_at < ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY created_at;

\echo ===LIVE_VS_PAPER_PNL===
SELECT
  o.execution_mode::text as mode,
  count(DISTINCT o.signal_id) as signals_with_orders,
  count(*) FILTER (WHERE o.state::text = 'FILLED') as filled_orders,
  count(*) FILTER (WHERE o.state::text = 'REJECTED') as rejected_orders
FROM oms_orders o
WHERE o.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND o.deleted = false
GROUP BY 1;

\echo ===STRATEGY_PNL_LIVE_ONLY===
SELECT s.strategy_name,
  count(*) as signals,
  round(sum(COALESCE(s.realized_pnl,0))::numeric,2) as total_pnl,
  count(*) FILTER (WHERE s.pipeline = 'LIVE') as live_pipeline,
  count(*) FILTER (WHERE s.pipeline = 'BOTH') as both_pipeline
FROM strategy_signals s
WHERE s.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND s.deleted = false AND s.is_test_trade = false
GROUP BY s.strategy_name ORDER BY total_pnl DESC;
"""

LOG_GREPS = [
    ("active_order", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -i 'active order already exists' | tail -100"),
    ("trader_not_found", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -i 'Trader account not found' | tail -100"),
    ("manual_exit", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -iE 'manual.?exit|EXIT_SYMBOL|flatten|suppression|CLOSED_BY_USER' | tail -200"),
    ("reconciliation", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -iE 'reconcil|BrokerPositionTruth|position sync' | tail -200"),
    ("orphan_redispatch", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -iE 'orphan|redispatch' | tail -100"),
    ("3pm_window", "docker logs stokr-api --since 2026-06-05T09:00:00 2>&1 | grep -iE '14:5|15:0|15:1|manual|flatten|EXIT|suppression' | tail -300"),
]

def run():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PASS, timeout=30)

    # Write and run SQL
    sftp = c.open_sftp()
    remote_sql = "/tmp/forensic_audit.sql"
    with sftp.file(remote_sql, "w") as f:
        f.write(SQL)
    sftp.close()

    print("=" * 80)
    print("DATABASE AUDIT")
    print("=" * 80)
    cmd = (
        f"docker cp {remote_sql} stokr-postgres:/tmp/forensic_audit.sql && "
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -f /tmp/forensic_audit.sql 2>&1"
    )
    _, o, e = c.exec_command(cmd, timeout=180)
    out = (o.read() + e.read()).decode("utf-8", errors="replace")
    print(out)

    print("=" * 80)
    print("LOG GREPS (IST trading window)")
    print("=" * 80)
    for name, grep_cmd in LOG_GREPS:
        print(f"\n--- LOG: {name} ---")
        _, o, e = c.exec_command(grep_cmd, timeout=120)
        log_out = (o.read() + e.read()).decode("utf-8", errors="replace")
        if log_out.strip():
            print(log_out)
        else:
            print("(no matches)")

    # Broker position snapshot from recent logs
    print("\n--- LOG: broker_positions_snapshot ---")
    _, o, e = c.exec_command(
        "docker logs stokr-api --since 2026-06-05T09:00:00 2>&1 | grep -iE 'broker.*position|netQty|positions.*net' | tail -50",
        timeout=60,
    )
    print((o.read() + e.read()).decode("utf-8", errors="replace") or "(no matches)")

    c.close()

if __name__ == "__main__":
    run()
