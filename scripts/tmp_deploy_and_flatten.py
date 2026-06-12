#!/usr/bin/env python3
"""Deploy latest Release_v2 and flatten Vishnu broker positions."""
import json
import paramiko
import time

HOST = "173.249.55.84"
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=600):
    print(f"\n>>> {cmd[:120]}...")
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode()
    print(out[-3000:] if len(out) > 3000 else out)
    return out

run("cd /opt/stokr/stokr-platform && git pull origin Release_v2", timeout=120)
run("cd /opt/stokr/stokr-platform && git log -1 --oneline")
run("cd /opt/stokr/stokr-platform && docker compose build api", timeout=900)
run("cd /opt/stokr/stokr-platform && docker compose up -d api", timeout=180)

for i in range(12):
    time.sleep(15)
    h = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/health", timeout=30)
    if "200" in h:
        print("API healthy")
        break

login = run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
token = json.loads(login.strip().split("\n")[-1])["data"]["accessToken"]

print("\n=== FLATTEN ===")
flat = run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {token}'""")
print(flat)

print("\n=== LIVE ORDERS LAST 5 MIN ===")
run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT symbol, side, quantity, state, execution_mode, strategy_key, reject_reason,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM oms_orders
WHERE user_id='{UID}' AND created_at > NOW() - INTERVAL '5 minutes'
ORDER BY created_at DESC LIMIT 30;" """)

print("\n=== TERMINAL_FLATTEN SUMMARY ===")
run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT execution_mode, state, count(*)
FROM oms_orders
WHERE user_id='{UID}' AND strategy_key='TERMINAL_FLATTEN'
  AND created_at > NOW() - INTERVAL '10 minutes'
GROUP BY execution_mode, state;" """)

c.close()
