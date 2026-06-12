#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

print("=== ORPHAN events last 90 min ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT created_at AT TIME ZONE 'Asia/Kolkata' AS ist, count(*)
FROM reconciliation_events
WHERE user_id='{UID}' AND discrepancy_type='ORPHAN_BROKER_POSITION'
  AND created_at > NOW() - INTERVAL '90 minutes'
GROUP BY 1 ORDER BY 1 DESC LIMIT 20;" """))

print("\n=== Latest ORPHAN at 15:03 batch ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, broker_qty, internal_qty, created_at AT TIME ZONE 'Asia/Kolkata'
FROM reconciliation_events
WHERE user_id='{UID}' AND discrepancy_type='ORPHAN_BROKER_POSITION'
  AND created_at > '2026-06-08 15:00:00+05:30'
ORDER BY created_at DESC LIMIT 20;" """))

print("\n=== LIVE net qty internal ledger ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) as net_qty
FROM oms_executions e
JOIN oms_orders o ON o.id = e.order_id
WHERE o.user_id='{UID}' AND o.execution_mode='LIVE' AND o.deleted=false
GROUP BY symbol
HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) <> 0
ORDER BY symbol;" """))

print("\n=== kite position fetch logs ===")
print(run("docker logs stokr-api --since 20m 2>&1 | grep -iE 'positions|kite.*position|fetchBroker|getPositions' | tail -20"))

c.close()
