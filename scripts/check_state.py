#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== Today's orders summary ===")
print(q("""SELECT state, execution_mode, count(*) FROM oms_orders 
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
GROUP BY state, execution_mode;"""))

print("\n=== LIVE FILLED with slippage ===")
print(q("""SELECT o.symbol, o.side, o.entry_reference_price as ref_price,
       x.avg_price as fill_price, x.slippage_bps,
       (x.avg_price - o.entry_reference_price) as price_diff
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.created_at;"""))

print("\n=== Check if any signals generated today ===")
print(q("""SELECT 'strategy_signals' as tbl, count(*) FROM strategy_signals 
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
UNION ALL
SELECT 'signal_pipeline_audit', count(*) FROM signal_pipeline_audit 
WHERE occurred_at > '2026-06-05 00:00 IST'::timestamptz;"""))

print("\n=== Latest logs from API container ===")
import datetime
r = subprocess.run(["docker","logs","stokr-api","--tail","50","--timestamps"], capture_output=True, text=True, timeout=10)
lines = r.stdout.strip().split('\n')
# Filter for ERROR or WARN or exception
for line in lines[-100:]:
    if any(w in line.upper() for w in ['ERROR', 'WARN', 'EXCEPTION', 'FATAL', 'SLIPPAGE', 'REJECT', 'FAIL']):
        print(line)
