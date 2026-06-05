#!/usr/bin/env python3
import paramiko

HOST, USER, PASS = "173.249.55.84", "root", "Temp1234.."

SQL = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on

\echo ===LIVE_USER===
SELECT id, email FROM users WHERE id = '6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4' OR email ILIKE '%stokr%' LIMIT 5;

\echo ===OPEN_PORTFOLIO_POSITIONS===
SELECT symbol, quantity, avg_price, strategy_key, user_id,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_upd
FROM portfolio_positions WHERE deleted = false AND quantity != 0
ORDER BY updated_at DESC;

\echo ===NONZERO_PORTFOLIO_ALL_USERS===
SELECT user_id, symbol, quantity, strategy_key
FROM portfolio_positions WHERE deleted = false AND quantity != 0;

\echo ===SIGNAL_OUTCOMES_TODAY===
SELECT outcome_status, count(*) FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false AND is_test_trade = false
GROUP BY outcome_status ORDER BY count DESC;

\echo ===MANUAL_OR_EXPIRY_TODAY===
SELECT id, strategy_name, symbol, outcome_status, expiry_reason,
  to_char(outcome_time AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_outcome,
  to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_upd
FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
  AND (outcome_status = 'MANUAL' OR expiry_reason ILIKE '%manual%' OR expiry_reason ILIKE '%terminal%'
       OR expiry_reason ILIKE '%operator%');

\echo ===SIGNALS_UPDATED_14_30_15_30===
SELECT id, strategy_name, symbol, outcome_status, expiry_reason, realized_pnl,
  to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_c,
  to_char(outcome_time AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_out
FROM strategy_signals
WHERE outcome_time >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND outcome_time < ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY outcome_time;

\echo ===TERMINAL_ORDERS_TODAY===
SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  symbol, side, execution_mode, state, strategy_key, reject_reason, signal_id,
  execution_linkage, execution_linkage_reason
FROM oms_orders
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
  AND (strategy_key IN ('TERMINAL_EXIT_SYMBOL','TERMINAL_FLATTEN','EXIT_SAFE')
       OR execution_linkage ILIKE '%terminal%' OR execution_linkage_reason ILIKE '%terminal%'
       OR idempotency_key ILIKE '%terminal%')
ORDER BY created_at;

\echo ===EXIT_ORDERS_AFTER_14_30===
SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  symbol, side, execution_mode, state, strategy_key, reject_reason, signal_id,
  broker_external_order_id, user_id
FROM oms_orders
WHERE created_at >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY created_at;

\echo ===LIVE_FILLS_DETAIL===
SELECT to_char(o.created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  o.symbol, o.side, o.state, o.execution_mode, o.strategy_key, o.signal_id,
  s.strategy_name, s.outcome_status, s.expiry_reason
FROM oms_orders o
LEFT JOIN strategy_signals s ON s.id = o.signal_id
WHERE o.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND o.deleted = false AND o.execution_mode = 'LIVE' AND o.state = 'FILLED'
ORDER BY o.created_at;

\echo ===RECON_EVENTS_SCHEMA===
SELECT column_name FROM information_schema.columns WHERE table_name='reconciliation_events' ORDER BY ordinal_position;

\echo ===RECON_EVENTS_TODAY===
SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  discrepancy_type, symbol, broker_qty, internal_qty, status, notes
FROM reconciliation_events
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY created_at DESC LIMIT 100;

\echo ===RECON_EVENTS_3PM===
SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist,
  discrepancy_type, symbol, broker_qty, internal_qty, status
FROM reconciliation_events
WHERE created_at >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND created_at < ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false
ORDER BY created_at;

\echo ===STRATEGY_INSTANCES_RUNTIME===
SELECT id, runtime_state, to_char(updated_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') as ist_upd
FROM strategy_instances WHERE deleted = false ORDER BY updated_at DESC LIMIT 20;

\echo ===OWNER_TYPE_TODAY===
SELECT owner_type, lifecycle_status, count(*) FROM strategy_signals
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false GROUP BY 1,2;

\echo ===GHOST_POSITION_SYMBOLS===
SELECT symbol, quantity, user_id FROM portfolio_positions
WHERE deleted = false AND user_id = '6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4'
ORDER BY symbol;

\echo ===OMS_TOTALS===
SELECT execution_mode, state, count(*) FROM oms_orders
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND deleted = false GROUP BY 1,2 ORDER BY 1,2;
"""

LOG_CMDS = [
    ("terminal_flatten", "docker logs stokr-api --since 2026-06-05T09:00:00 2>&1 | grep -iE 'TraderTerminal|FLATTEN|EXIT_ALL|terminal_exit|manual_exit_suppressed|suppressAutoExit' | tail -150"),
    ("3pm_exact", "docker logs stokr-api --since 2026-06-05T09:25:00 2>&1 | grep -iE '15:0[0-9]:|15:1[0-9]:|15:2[0-5]:' | grep -iE 'terminal|flatten|EXIT_ALL|manual|suppression|kill_switch|CLOSED' | tail -200"),
    ("broker_truth_3pm", "docker logs stokr-api --since 2026-06-05T09:25:00 2>&1 | grep 'broker.truth' | grep -E '15:0|15:1|15:2' | head -80"),
    ("active_order_logs", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -i 'active order' | wc -l"),
    ("trader_account_logs", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -i 'Trader account not found' | wc -l"),
]

def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PASS, timeout=30)
    sftp = c.open_sftp()
    with sftp.file("/tmp/audit2.sql", "w") as f:
        f.write(SQL)
    sftp.close()
    _, o, e = c.exec_command(
        "docker cp /tmp/audit2.sql stokr-postgres:/tmp/audit2.sql && "
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -f /tmp/audit2.sql 2>&1",
        timeout=180,
    )
    print("=== SQL ===")
    print((o.read() + e.read()).decode("utf-8", errors="replace"))
    print("=== LOGS ===")
    for name, cmd in LOG_CMDS:
        print(f"\n--- {name} ---")
        _, o, e = c.exec_command(cmd, timeout=120)
        print((o.read() + e.read()).decode("utf-8", errors="replace") or "(empty)")
    c.close()

if __name__ == "__main__":
    main()
