#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== OMS orders TODAY (2026-06-05 IST) ===")
print(q("SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, symbol, side, state, broker_order_id, execution_mode, strategy_key, reject_reason FROM oms_orders WHERE created_at > '2026-06-05 00:00 IST'::timestamptz ORDER BY created_at DESC LIMIT 20;"))

print("\n=== OMS orders TODAY by state ===")
print(q("SELECT state, execution_mode, count(*) FROM oms_orders WHERE created_at > '2026-06-05 00:00 IST'::timestamptz GROUP BY state, execution_mode;"))

print("\n=== All LIVE orders rejected (reject_reason) ===")
print(q("SELECT reject_reason, count(*) FROM oms_orders WHERE execution_mode = 'LIVE' AND state IN ('REJECTED', 'FAILED') AND reject_reason IS NOT NULL GROUP BY reject_reason ORDER BY count(*) DESC LIMIT 20;"))

print("\n=== Most recent 20 LIVE orders with details ===")
print(q("SELECT created_at, symbol, side, state, broker_order_id, reject_reason, strategy_key FROM oms_orders WHERE execution_mode = 'LIVE' ORDER BY created_at DESC LIMIT 20;"))

print("\n=== Live orders by state (all time) ===")
print(q("SELECT state, count(*) FROM oms_orders WHERE execution_mode = 'LIVE' GROUP BY state;"))

print("\n=== Broker orders filled (LIVE) ===")
print(q("SELECT created_at, symbol, side, quantity, limit_price, broker_order_id, strategy_key FROM oms_orders WHERE execution_mode = 'LIVE' AND state = 'FILLED' ORDER BY created_at DESC LIMIT 20;"))

print("\n=== OMS count today ===")
print(q("SELECT count(*) FROM oms_orders WHERE created_at > '2026-06-05 00:00 IST'::timestamptz;"))
