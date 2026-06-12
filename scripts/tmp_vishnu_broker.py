#!/usr/bin/env python3
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
print(run("""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "\\d broker_accounts" """))
print(run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT * FROM broker_accounts WHERE user_id='{UID}';" """))

login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
admin = login["data"]["accessToken"]

# try admin endpoints for user broker
for path in [
    f"/api/admin/users/{UID}/broker",
    f"/api/admin/trader/{UID}/workstation",
    f"/api/admin/users/{UID}/zerodha/status",
]:
    print(f"\n=== {path} ===")
    print(run(f"curl -s -H 'Authorization: Bearer {admin}' 'http://127.0.0.1:8080{path}'")[:2500])

# login as vishnu if we know password - skip, use internal API
print("\n=== flatten with logs ===")
print(run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {admin}'"""))
print(run("docker logs stokr-api --since 1m 2>&1 | grep -iE 'flatten|fetch_failed|token|broker.truth|7c7aca21' | tail -15"))

c.close()
