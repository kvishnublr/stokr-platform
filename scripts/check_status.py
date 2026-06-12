#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== KILL SWITCH STATUS ===")
print(q("SELECT id, reason, active, triggered_at AT TIME ZONE 'Asia/Kolkata' as ist FROM trading_kill_switch_events ORDER BY id DESC LIMIT 5;"))

print("\n=== CURRENT OPEN LIVE POSITIONS ===")
print(q("""SELECT o.symbol, o.side, o.strategy_key, 
       x.avg_price as fill, o.entry_reference_price as ref,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as entry_ist,
       (CASE WHEN o.side='BUY' THEN x.avg_price - o.entry_reference_price ELSE o.entry_reference_price - x.avg_price END) as ref_pnl
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.paired_order_id IS NULL
  AND o.created_at > '2026-06-01'
ORDER BY o.symbol;"""))

print("\n=== RUNNING SIGNALS (not yet closed) ===")
print(q("""SELECT s.symbol, s.signal_type, s.entry_price, s.outcome_status,
       s.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM strategy_signals s
WHERE (s.outcome_status IS NULL OR s.outcome_status = '' OR s.outcome_status = 'RUNNING')
  AND s.created_at > '2026-06-08 00:00 IST'::timestamptz
ORDER BY s.created_at;"""))

print("\n=== API/CONTAINER HEALTH ===")
import subprocess as sp
r = sp.run(["curl","-s","-o","/dev/null","-w","%{http_code}","--connect-timeout","5","http://localhost:8080/api/health"], capture_output=True, text=True)
print(f"API health endpoint: {r.stdout.strip() or r.stderr.strip()}")
r2 = sp.run(["docker","ps","--format","{{.Names}} {{.Status}}"], capture_output=True, text=True)
print(f"\n{r2.stdout.strip()}")

print("\n=== RECENT 5 LIVE FILLED (last hour) ===")
print(q("""SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.symbol, o.side, o.strategy_key, x.avg_price
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.created_at > NOW() - INTERVAL '1 hour'
ORDER BY o.created_at DESC LIMIT 5;"""))
