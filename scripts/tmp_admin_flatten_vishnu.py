#!/usr/bin/env python3
import json
import time
import paramiko

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=180)
    return (o.read() + e.read()).decode()

print("waiting for api...")
for _ in range(24):
    h = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health")
    if "200" in h:
        break
    time.sleep(10)

login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
token = login["data"]["accessToken"]

print("=== FLATTEN broker positions ===")
flat = run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {token}'""")
print(flat[:4000])

print("\n=== BACKFILL outcome exits (24h) ===")
back = run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/signals/backfill-outcome-exits?lookbackHours=24&maxSignals=100' -H 'Authorization: Bearer {token}'""")
print(back[:2000])

print("\n=== LIVE outcome-exit orders last hour ===")
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "SELECT symbol, side, quantity, state, execution_mode, created_at AT TIME ZONE 'Asia/Kolkata' FROM oms_orders WHERE user_id='{UID}' AND idempotency_key LIKE 'outcome-exit:%' AND created_at > NOW() - INTERVAL '1 hour' ORDER BY created_at DESC LIMIT 15;" """))

c.close()
