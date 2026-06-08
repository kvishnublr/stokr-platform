#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT broker_vendor, connection_status, token_valid, token_expires_at AT TIME ZONE 'Asia/Kolkata',
       last_connected_at AT TIME ZONE 'Asia/Kolkata', updated_at AT TIME ZONE 'Asia/Kolkata'
FROM broker_accounts WHERE user_id='{UID}';" """))

print("\n=== ORPHAN broker positions in reconciliation (latest per symbol) ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT DISTINCT ON (symbol) symbol, discrepancy_type, broker_qty, internal_qty,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM reconciliation_events
WHERE user_id='{UID}' AND discrepancy_type='ORPHAN_BROKER_POSITION'
ORDER BY symbol, created_at DESC;" """))

print("\n=== zerodha logs ===")
print(run("docker logs stokr-api --since 10m 2>&1 | grep -iE 'zerodha|oauth|token|broker.*disconnect|fetch_failed' | tail -25"))

c.close()
