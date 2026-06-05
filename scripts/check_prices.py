#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== 1. ICICIBANK LIVE trade execution detail ===")
print(q("""SELECT x.id, x.order_id, x.avg_price, x.reference_price, x.execution_kind,
       x.slippage_bps, x.fill_time AT TIME ZONE 'Asia/Kolkata' as fill_ist,
       x.execution_hash, x.broker_execution_id
FROM oms_executions x
JOIN oms_orders o ON x.order_id = o.id
WHERE o.symbol = 'ICICIBANK' AND o.execution_mode = 'LIVE'
ORDER BY x.created_at DESC LIMIT 5;"""))

print("\n=== 2. ICICIBANK oms_trades ===")
print(q("""SELECT t.price, t.quantity FROM oms_trades t
JOIN oms_orders o ON t.order_id = o.id
WHERE o.symbol = 'ICICIBANK' AND o.execution_mode = 'LIVE';"""))

print("\n=== 3. Check if all LIVE FILLED today have oms_executions entries ===")
print(q("""SELECT o.symbol, o.side, o.entry_reference_price,
       x.avg_price, x.reference_price, x.execution_kind, x.slippage_bps
FROM oms_orders o
LEFT JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode = 'LIVE' AND o.state = 'FILLED'
  AND o.created_at > '2026-06-04 00:00 IST'::timestamptz
ORDER BY o.created_at DESC;"""))

print("\n=== 4. Check BROKER_SYNC events payload for today for fill prices ===")
print(q("""SELECT e.event_type, substring(e.event_payload_json, 1, 500) as payload,
       e.occurred_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_execution_events e
WHERE e.event_type = 'ORDER_FILLED'
  AND e.occurred_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY e.occurred_at;"""))

print("\n=== 5. What was the exit/fill for ICICIBANK? ===")
print(q("""SELECT e.event_type, e.occurred_at AT TIME ZONE 'Asia/Kolkata' as ist,
       substring(e.event_payload_json, 1, 300) as payload
FROM oms_execution_events e
JOIN oms_orders o ON e.order_id = o.id
WHERE o.symbol = 'ICICIBANK' AND o.execution_mode = 'LIVE'
ORDER BY e.occurred_at;"""))
