#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== 1. All reject_reasons from today ===")
print(q("""SELECT o.reject_reason, o.symbol, o.strategy_key, o.user_id, 
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.state IN ('REJECTED', 'FAILED')
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.created_at DESC LIMIT 20;"""))

print("\n=== 2. All unique user_ids in oms_orders today ===")
print(q("""SELECT DISTINCT o.user_id FROM oms_orders o
WHERE o.created_at > '2026-06-05 00:00 IST'::timestamptz;"""))

print("\n=== 3. Check broker_accounts without join ===")
print(q("""SELECT id, user_id, vendor_code, broker_user_id, status 
FROM broker_accounts;"""))

print("\n=== 4. Check what max_position limit is applied ===")
print(q("""SELECT id, symbol, reject_reason, strategy_key, user_id
FROM oms_orders 
WHERE reject_reason LIKE '%max position%'
ORDER BY created_at DESC LIMIT 5;"""))

print("\n=== 5. execution_mode and state counts per strategy today ===")
print(q("""SELECT strategy_key, state, execution_mode, count(*)
FROM oms_orders
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
GROUP BY strategy_key, state, execution_mode
ORDER BY strategy_key, state;"""))

print("\n=== 6. Broker mismatch - check actual reject_reason with broader search ===")
print(q("""SELECT symbol, strategy_key, reject_reason, state, execution_mode, user_id,
       created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
  AND reject_reason IS NOT NULL AND reject_reason != ''
ORDER BY created_at;"""))

print("\n=== 7. Check execution events for broker mismatch ===")
print(q("""SELECT e.event_type, e.occurred_at AT TIME ZONE 'Asia/Kolkata' as ist,
       substring(e.event_payload_json, 1, 400) as payload
FROM oms_execution_events e
WHERE e.event_type LIKE '%REJECT%'
  AND e.occurred_at > '2026-06-05 10:40:00 IST'::timestamptz
ORDER BY e.occurred_at DESC LIMIT 10;"""))
