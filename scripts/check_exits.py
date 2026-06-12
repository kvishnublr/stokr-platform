#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

# Compare signal exits (using ref price) vs actual fills
print("=== EXIT STRATEGY EVALUATION ===")
print("")

print("--- Today's LIVE trades: Signal PnL vs Actual PnL ---")
rows = q("""SELECT s.symbol, s.signal_type as side, 
       s.entry_price as signal_entry, s.exit_price as signal_exit,
       s.realized_pnl as signal_pnl, s.outcome_status,
       x.avg_price as actual_fill,
       round((CASE WHEN s.signal_type='BUY' THEN x.avg_price - s.entry_price ELSE s.entry_price - x.avg_price END)::numeric, 2) as entry_diff,
       round((CASE WHEN s.signal_type='BUY' THEN s.exit_price - x.avg_price ELSE x.avg_price - s.exit_price END)::numeric, 2) as exit_diff,
       s.created_at AT TIME ZONE 'Asia/Kolkata' as signal_ist,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as order_ist
FROM strategy_signals s
JOIN oms_orders o ON o.signal_id = s.id
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode = 'LIVE' AND o.state = 'FILLED'
  AND s.outcome_status IS NOT NULL
ORDER BY o.created_at;""").split('\n')
print(f"{'Symbol':<12} {'Side':<5} {'S.Entry':<9} {'S.Exit':<9} {'S.PnL':<8} {'Outcome':<17} {'A.Fill':<9} {'EntryDiff':<9} {'ExitDiff':<9}")
print("-" * 87)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<12} {parts[1]:<5} {parts[2]:<9} {parts[3]:<9} {parts[4]:<8} {parts[5]:<17} {parts[6]:<9} {parts[7]:<9} {parts[8]:<9}")

print("\n")
print("--- Exit type distribution (all signals today) ---")
print(q("""SELECT outcome_status, count(*) as cnt,
       round(avg(realized_pnl)::numeric, 2) as avg_pnl,
       round(sum(realized_pnl)::numeric, 2) as total_pnl
FROM strategy_signals 
WHERE created_at > '2026-06-08 00:00 IST'::timestamptz
  AND outcome_status IS NOT NULL AND outcome_status != ''
GROUP BY outcome_status
ORDER BY cnt DESC;"""))

print("\n")
print("--- Per-strategy exit outcomes ---")
print(q("""SELECT s.execution_mode, s.outcome_status, count(*) as cnt,
       round(avg(realized_pnl)::numeric, 2) as avg_pnl
FROM strategy_signals s
WHERE s.created_at > '2026-06-08 00:00 IST'::timestamptz
  AND s.outcome_status IS NOT NULL AND s.outcome_status != ''
GROUP BY s.execution_mode, s.outcome_status
ORDER BY s.execution_mode, cnt DESC;"""))

print("\n")
print("--- Time-to-exit analysis (minutes held) ---")
print(q("""SELECT s.symbol, s.signal_type, s.outcome_status, 
       s.entry_price, s.exit_price, s.realized_pnl,
       round(extract(epoch from (s.outcome_time - s.created_at))/60) as minutes_held
FROM strategy_signals s
WHERE s.created_at > '2026-06-08 00:00 IST'::timestamptz
  AND s.outcome_status IS NOT NULL AND s.outcome_status != ''
  AND s.outcome_time IS NOT NULL
ORDER BY minutes_held DESC NULLS LAST
LIMIT 20;"""))
