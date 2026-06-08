#!/usr/bin/env python3
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

login = json.loads(run("""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""))
admin = login["data"]["accessToken"]

body = json.dumps({"userId": UID})
print("=== zerodha test-session ===")
out = run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/brokers/orchestration/zerodha/test-session' -H 'Authorization: Bearer {admin}' -H 'Content-Type: application/json' -d '{body}'""")
print(out[:5000])

print("\n=== flatten again ===")
print(run(f"""curl -s -X POST 'http://127.0.0.1:8080/api/admin/trader/{UID}/flatten-broker-positions' -H 'Authorization: Bearer {admin}'"""))

c.close()
