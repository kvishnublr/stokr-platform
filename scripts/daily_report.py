#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=" * 100)
print("STOKR PLATFORM - DAILY TRADING REPORT")
print("Date: 2026-06-05 (IST)")
print("=" * 100)

# SECTION 1: OVERALL SUMMARY
print("\n" + "=" * 100)
print("1. OVERALL ORDER SUMMARY")
print("=" * 100)
rows = q("""SELECT execution_mode, state, count(*) as cnt FROM oms_orders 
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
GROUP BY execution_mode, state ORDER BY execution_mode, state;""").split('\n')
print(f"{'Mode':<12} {'State':<20} {'Count':<8}")
print("-" * 40)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<12} {parts[1]:<20} {parts[2]:<8}")

total = sum(int(r.split('|')[2]) for r in rows if r.strip() and len(r.split('|'))==3)
print("-" * 40)
print(f"{'TOTAL':<33} {total:<8}")

# SECTION 2: BY STRATEGY
print("\n" + "=" * 100)
print("2. ORDERS BY STRATEGY")
print("=" * 100)
rows = q("""SELECT strategy_key, execution_mode, state, count(*) as cnt FROM oms_orders 
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
GROUP BY strategy_key, execution_mode, state ORDER BY strategy_key, execution_mode, state;""").split('\n')
print(f"{'Strategy':<22} {'Mode':<10} {'State':<15} {'Count':<6}")
print("-" * 55)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<22} {parts[1]:<10} {parts[2]:<15} {parts[3]:<6}")

# SECTION 3: FILLED LIVE ORDERS WITH SLIPPAGE
print("\n" + "=" * 100)
print("3. FILLED LIVE ORDERS (with slippage analysis)")
print("=" * 100)
rows = q("""SELECT o.strategy_key, o.symbol, o.side, 
       o.entry_reference_price as ref_price, 
       x.avg_price as fill_price, 
       round((x.avg_price - o.entry_reference_price)::numeric, 2) as abs_slippage,
       round(x.slippage_bps::numeric, 1) as slippage_bps,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.created_at;""").split('\n')
print(f"{'Strategy':<20} {'Symbol':<14} {'Side':<6} {'Ref Price':<10} {'Fill Price':<10} {'Slippage':<10} {'BPS':<8} {'Time':<14}")
print("-" * 92)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<20} {parts[1]:<14} {parts[2]:<6} {parts[3]:<10} {parts[4]:<10} {parts[5]:<10} {parts[6]:<8} {parts[7]:<14}")

# SECTION 4: REJECTIONS BY REASON
print("\n" + "=" * 100)
print("4. REJECTION ANALYSIS")
print("=" * 100)
rows = q("""SELECT reject_reason, execution_mode, count(*) as cnt FROM oms_orders 
WHERE created_at > '2026-06-05 00:00 IST'::timestamptz
  AND state = 'REJECTED' AND reject_reason IS NOT NULL AND reject_reason != ''
GROUP BY reject_reason, execution_mode ORDER BY cnt DESC;""").split('\n')
print(f"{'Reason':<55} {'Mode':<8} {'Count':<6}")
print("-" * 70)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<55} {parts[1]:<8} {parts[2]:<6}")

# SECTION 5: SIMULATION (USER 3333) ORDERS
print("\n" + "=" * 100)
print("5. SIMULATION ENGINE ACTIVITY (user 3333...)")
print("=" * 100)
rows = q("""SELECT o.strategy_key, o.symbol, o.state, o.execution_mode, o.reject_reason,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.user_id = '33333333-3333-3333-3333-333333333333'
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.created_at;""").split('\n')
print(f"{'Strategy':<20} {'Symbol':<14} {'State':<10} {'Mode':<8} {'Reject Reason':<40} {'Time':<14}")
print("-" * 106)
for r in rows:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<20} {parts[1]:<14} {parts[2]:<10} {parts[3]:<8} {parts[4]:<40} {parts[5]:<14}")

# SECTION 6: CURRENT OPEN POSITIONS (from reconciliation)
print("\n" + "=" * 100)
print("6. CURRENT OPEN POSITIONS (per broker reconciliation)")
print("=" * 100)
rows = q("""SELECT 
  split_part(symbol, ':', 2) as raw_symbol,
  CASE WHEN broker > 0 THEN 'LONG' WHEN broker < 0 THEN 'SHORT' END as side,
  abs(broker)::int as qty,
  'ORPHAN_BROKER_POSITION' as status
FROM (
  SELECT regexp_replace(log, '.*symbol=(\\S+) broker=([-\\.0-9]+) internal.*', '\\1|\\2') as kv
  FROM (SELECT unnest(regexp_matches(message, 'ORPHAN_BROKER_POSITION symbol=[^ ]+ broker=[^ ]+ internal=[^ ]+', 'g')) as log FROM (
    SELECT data::json->>'message' as message FROM (
      SELECT regexp_matches(log_line, '\\{.*\\}') as data FROM (
        SELECT datal as log_line FROM (
          SELECT regexp_split_to_table(logs, '\n') as datal
          FROM (SELECT (SELECT string_agg(line, '\n') FROM (
            SELECT regexp_split_to_table(log, '\n') as line
            FROM (SELECT (SELECT log FROM (SELECT 1) dummy) as log) sub
          ) sub2) as logs) sub3
        ) sub4
      ) sub5
    ) sub6
  ) sub7
) sub8
WHERE kv LIKE '%|%'
LIMIT 20;""").split('\n')
# Simpler approach - use broker_execution_telemetry for open positions
print("(Using broker reconciliation data from logs instead)")
rows2 = q("""SELECT o.symbol, o.side, o.quantity, o.entry_reference_price, 
       x.avg_price, o.strategy_key, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
  AND o.id NOT IN (
    SELECT paired_order_id FROM oms_orders 
    WHERE paired_order_id IS NOT NULL AND state='FILLED'
  )
ORDER BY o.symbol;""").split('\n')
print(f"{'Symbol':<14} {'Side':<6} {'Qty':<4} {'Entry':<10} {'Fill':<10} {'Strategy':<20} {'Time':<14}")
print("-" * 78)
for r in rows2:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<14} {parts[1]:<6} {parts[2]:<4} {parts[3]:<10} {parts[4]:<10} {parts[5]:<20} {parts[6]:<14}")

# SECTION 7: PNL ESTIMATION
print("\n" + "=" * 100)
print("7. ESTIMATED PnL (based on actual fill prices vs current)")
print("=" * 100)
# Get current LTP from strategy_signals or use a reasonable estimate
print("Note: Live PnL requires current LTP from feed. Estimates shown:")
rows3 = q("""SELECT o.symbol, o.side, 
       x.avg_price as entry_price,
       CASE WHEN o.side = 'BUY' THEN 'LTP - entry' ELSE 'entry - LTP' END as pnl_formula,
       round((x.avg_price - o.entry_reference_price)::numeric, 2) as val_vs_ref,
       x.slippage_bps,
       o.strategy_key
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.execution_mode='LIVE' AND o.state='FILLED'
  AND o.created_at > '2026-06-05 00:00 IST'::timestamptz
ORDER BY o.symbol;""").split('\n')
print(f"{'Symbol':<14} {'Side':<6} {'Entry':<10} {'Formula':<18} {'RefDiff':<10} {'BPS':<8} {'Strategy':<20}")
print("-" * 86)
for r in rows3:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<14} {parts[1]:<6} {parts[2]:<10} {parts[3]:<18} {parts[4]:<10} {parts[5]:<8} {parts[6]:<20}")

# SECTION 8: TIMELINE
print("\n" + "=" * 100)
print("8. TODAY'S TRADING TIMELINE")
print("=" * 100)
rows4 = q("""SELECT o.created_at AT TIME ZONE 'Asia/Kolkata' as ist, o.strategy_key, o.symbol, o.side, o.state, o.execution_mode, o.reject_reason
FROM oms_orders o
WHERE o.created_at > '2026-06-05 00:00 IST'::timestamptz
  AND o.execution_mode = 'LIVE'
ORDER BY o.created_at;""").split('\n')
print(f"{'Time':<14} {'Strategy':<20} {'Symbol':<14} {'Side':<6} {'State':<10} {'Reject Reason'}")
print("-" * 110)
for r in rows4:
    if r.strip():
        parts = r.split('|')
        rr = parts[6] if len(parts) > 6 else ''
        print(f"{parts[0]:<14} {parts[1]:<20} {parts[2]:<14} {parts[3]:<6} {parts[4]:<10} {parts[5]:<8} {rr}")

# SECTION 9: STRATEGIES RUNNING
print("\n" + "=" * 100)
print("9. STRATEGY INSTANCES STATUS")
print("=" * 100)
rows5 = q("""SELECT sd.strategy_key, sd.asset_class, sd.segment, si.execution_mode, si.runtime_state
FROM strategy_instances si
JOIN strategy_definitions sd ON si.definition_id = sd.id
WHERE si.user_id = '6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4'
ORDER BY sd.strategy_key;""").split('\n')
print(f"{'Strategy':<22} {'Asset':<12} {'Segment':<10} {'Mode':<10} {'State':<10}")
print("-" * 64)
for r in rows5:
    if r.strip():
        parts = r.split('|')
        print(f"{parts[0]:<22} {parts[1]:<12} {parts[2]:<10} {parts[3]:<10} {parts[4]:<10}")

# SECTION 10: TARGET / STOPLOSS / CLOSED POSITIONS
print("\n" + "=" * 100)
print("10. CLOSED POSITIONS (Target/Stoploss hits)")
print("=" * 100)
rows6 = q("""SELECT o.symbol, o.side, o.strategy_key, o.execution_mode,
       o.entry_reference_price, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
       x.avg_price
FROM oms_orders o
JOIN oms_executions x ON x.order_id = o.id
WHERE o.state = 'FILLED'
  AND o.created_at > '2026-06-04 00:00 IST'::timestamptz
  AND o.paired_order_id IS NOT NULL
ORDER BY o.created_at DESC LIMIT 10;""").split('\n')
if rows6 and rows6[0].strip():
    for r in rows6:
        if r.strip():
            print(r)
else:
    print("No paired (closed) positions found today")

print("\n" + "=" * 100)
print("END OF REPORT")
print("=" * 100)
