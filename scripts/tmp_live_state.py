#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

print("=== LIVE FILLED entries without outcome exit ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT o.symbol, o.side, o.quantity, o.state, o.signal_id IS NOT NULL as has_signal, o.created_at AT TIME ZONE 'Asia/Kolkata'
FROM oms_orders o
WHERE o.user_id='{UID}' AND o.execution_mode='LIVE' AND o.state='FILLED' AND o.deleted=false
ORDER BY o.created_at DESC LIMIT 30;" """))

print("\n=== Recent ORPHAN symbols (15:02-15:03) ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT DISTINCT symbol, broker_qty
FROM reconciliation_events
WHERE user_id='{UID}' AND discrepancy_type='ORPHAN_BROKER_POSITION'
  AND created_at BETWEEN '2026-06-08 15:02:00+05:30' AND '2026-06-08 15:04:00+05:30'
ORDER BY symbol;" """))

print("\n=== Place flatten using internal fallback check - open signals ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT count(*) FILTER (WHERE outcome_status IS NULL OR outcome_status='') as open_signals,
       count(*) FILTER (WHERE outcome_status IS NOT NULL AND outcome_status <> '') as closed_signals
FROM strategy_signals WHERE user_id='{UID}' AND deleted=false AND created_at > NOW() - INTERVAL '7 days';" """))

c.close()
