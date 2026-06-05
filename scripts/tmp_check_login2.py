#!/usr/bin/env python3
import json
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=30):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:2000]}\n")

payload = json.dumps({"principal": "admin@stokr.local", "password": "password"})
run(f"curl -s -w '\\nHTTP=%{{http_code}}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -d '{payload}'")

payload2 = json.dumps({"principal": "admin@stokr.local", "password": "password123"})
run(f"curl -s -w '\\nHTTP=%{{http_code}}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -d '{payload2}'")

run("""curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -H 'Authorization: Bearer invalid.jwt.token' -d '{"principal":"admin@stokr.local","password":"password"}'""")

run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c "SELECT password_hash FROM auth_users WHERE email='admin@stokr.local';" """)
c.close()
