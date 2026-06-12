#!/usr/bin/env python3
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read()+e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
token = login["data"]["accessToken"]

# trader workstation broker positions endpoint
for path in [
    f"/api/trader/execution/broker-positions",
    f"/api/trader/terminal/workstation",
    f"/api/admin/trader/{UID}/broker-status",
]:
    print(f"\n=== GET {path} ===")
    print(run(f"curl -s -H 'Authorization: Bearer {token}' 'http://127.0.0.1:8080{path}'")[:4000])

print("\n=== recent reconciliation ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, discrepancy_type, broker_qty, internal_qty,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM reconciliation_events
WHERE user_id='{UID}' AND created_at > NOW() - INTERVAL '30 minutes'
ORDER BY created_at DESC LIMIT 25;" """))

print("\n=== broker truth logs last 2m ===")
print(run("docker logs stokr-api --since 3m 2>&1 | grep -iE 'broker.truth|ORPHAN_BROKER|fetch_failed|flatten|7c7aca21' | tail -30"))

c.close()
