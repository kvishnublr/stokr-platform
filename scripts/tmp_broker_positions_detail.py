#!/usr/bin/env python3
import json
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT cancellation_reason, rejection_reason, state, count(*)
FROM oms_orders WHERE user_id='{UID}' AND idempotency_key LIKE 'outcome-exit:%'
GROUP BY 1,2,3 ORDER BY count DESC;" """))

print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT column_name FROM information_schema.columns WHERE table_name='broker_accounts' ORDER BY 1;" """))

print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT * FROM broker_accounts WHERE user_id='{UID}' AND deleted=false LIMIT 1;" """))

login = run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
token = json.loads(login)["data"]["accessToken"]
diag = run(f"""curl -s 'http://127.0.0.1:8080/api/admin/oms/diagnostics?userId={UID}' -H 'Authorization: Bearer {token}'""")
print("diagnostics:", diag[:3000])

c.close()
