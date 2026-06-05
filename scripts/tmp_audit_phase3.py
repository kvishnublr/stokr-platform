#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

cmds = [
    ("terminal_posts", "docker logs stokr-api --since 2026-06-05T08:30:00 2>&1 | grep -iE 'terminal/control|/control/execute|EXIT_ALL|FLATTEN' | tail -100"),
    ("suppression_logs", "docker logs stokr-api --since 2026-06-05T08:30:00 2>&1 | grep manual_exit_suppressed | wc -l"),
    ("external_exit_logs", "docker logs stokr-api --since 2026-06-05T08:30:00 2>&1 | grep 'broker.truth.external_exit' | grep -v pending | head -30"),
    ("active_order_count", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -c 'active order' || true"),
    ("trader_not_found_count", "docker logs stokr-api --since 2026-06-05T03:30:00 2>&1 | grep -c 'Trader account not found' || true"),
    ("live_net_ledger", """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol,
  sum(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as net_qty,
  count(*) FILTER (WHERE state='FILLED') as filled_cnt
FROM oms_orders
WHERE deleted=false AND user_id='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4'
  AND execution_mode='LIVE' AND state='FILLED'
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
GROUP BY symbol ORDER BY symbol;" """),
    ("live_orders_all", """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata','HH24:MI:SS') ist,
  symbol, side, state, strategy_key, reject_reason, signal_id
FROM oms_orders
WHERE deleted=false AND user_id='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4'
  AND execution_mode='LIVE'
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
ORDER BY created_at;" """),
    ("signals_live_symbols", """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT s.symbol, s.strategy_name, s.outcome_status, s.expiry_reason,
  to_char(s.outcome_time AT TIME ZONE 'Asia/Kolkata','HH24:MI:SS') ist_out
FROM strategy_signals s
WHERE s.deleted=false AND s.is_test_trade=false
  AND s.symbol IN ('TATASTEEL','CASTROLIND','DRREDDY','TITAN','HDFCLIFE','BAJFINANCE','WIPRO','AXISBANK')
  AND s.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
ORDER BY s.symbol, s.created_at;" """),
    ("outcomes_after_14", """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT to_char(outcome_time AT TIME ZONE 'Asia/Kolkata','HH24:MI:SS') ist,
  strategy_name, symbol, outcome_status, expiry_reason
FROM strategy_signals
WHERE deleted=false AND outcome_time >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours') AT TIME ZONE 'Asia/Kolkata'
ORDER BY outcome_time;" """),
    ("recon_3pm_count", """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT discrepancy_type, count(*)
FROM reconciliation_events
WHERE deleted=false AND created_at >= ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '14 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
  AND created_at < ((CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') + interval '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
GROUP BY 1 ORDER BY 2 DESC;" """),
    ("orphan_broker_1513", "docker logs stokr-api --since 2026-06-05T09:40:00 2>&1 | grep ORPHAN_BROKER | grep '15:13' | head -5"),
    ("kill_switch", "docker logs stokr-api --since 2026-06-05T09:00:00 2>&1 | grep kill_switch | tail -20"),
]

for name, cmd in cmds:
    print(f"\n{'='*60}\n{name}\n{'='*60}")
    _, o, e = c.exec_command(cmd, timeout=120)
    print((o.read() + e.read()).decode("utf-8", errors="replace"))
c.close()
