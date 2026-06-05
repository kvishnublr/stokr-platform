#!/usr/bin/env python3
import subprocess, json
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== 1. ALL TATASTEEL orders today ===")
print(q("""SELECT o.id, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.side, o.quantity, 
       o.limit_price, o.entry_reference_price, o.state, o.execution_mode, o.strategy_key,
       o.broker_order_id, o.broker_external_order_id, o.reject_reason, o.user_id
FROM oms_orders o
WHERE o.symbol = 'TATASTEEL' AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.created_at;"""))

print("\n=== 2. TATASTEEL executions ===")
print(q("""SELECT x.id, x.order_id, x.avg_price, x.reference_price, x.execution_kind,
       x.slippage_bps, x.fill_time AT TIME ZONE 'Asia/Kolkata' as fill_ist,
       x.broker_execution_id, x.latency_ms
FROM oms_executions x
JOIN oms_orders o ON x.order_id = o.id
WHERE o.symbol = 'TATASTEEL' AND o.created_at > '2026-06-05 00:00 IST'::timestamptz;"""))

print("\n=== 3. TATASTEEL execution events ===")
print(q("""SELECT e.event_type, e.occurred_at AT TIME ZONE 'Asia/Kolkata' as ist, 
       substring(e.event_payload_json, 1, 400) as payload
FROM oms_execution_events e
JOIN oms_orders o ON e.order_id = o.id
WHERE o.symbol = 'TATASTEEL' AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY e.occurred_at;"""))

print("\n=== 4. TATASTEEL broker telemetry ===")
print(q("""SELECT * FROM broker_execution_telemetry 
WHERE symbol = 'TATASTEEL' ORDER BY created_at DESC LIMIT 3;"""))

print("\n=== 5. Strategy instances for PRE_OPEN_GAP_OI (TATASTEEL's strategy) ===")
print(q("""SELECT si.runtime_state, si.execution_mode, si.definition_id,
       sd.strategy_key, sd.segment, sd.asset_class
FROM strategy_instances si
JOIN strategy_definitions sd ON si.definition_id = sd.id
WHERE sd.strategy_key = 'PRE_OPEN_GAP_OI';"""))

print("\n=== 6. Check PRE_OPEN_GAP_OI strategy execution config ===")
print(q("""SELECT * FROM strategy_execution_configs sec
JOIN strategy_definitions sd ON sec.strategy_id = sd.id
WHERE sd.strategy_key = 'PRE_OPEN_GAP_OI';"""))

print("\n=== 7. TATASTEEL positions from yesterday that might ghost ===")
print(q("""SELECT o.id, o.state, o.execution_mode, o.reject_reason, o.entry_reference_price,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.symbol = 'TATASTEEL'
ORDER BY o.created_at DESC LIMIT 10;"""))

print("\n=== 8. Check ALL TATASTEEL orders ever (last 10) ===")
print(q("""SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.side, o.state, 
       o.execution_mode, o.strategy_key, o.entry_reference_price, x.avg_price
FROM oms_orders o
LEFT JOIN oms_executions x ON x.order_id = o.id
WHERE o.symbol = 'TATASTEEL'
ORDER BY o.created_at DESC LIMIT 15;"""))
