#!/usr/bin/env python3
import json
import paramiko

HOST = "173.249.55.84"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=30):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()}\n")
    return out

payload = json.dumps({"principal": "admin@stokr.local", "password": "Admin123!"})
run(f"curl -s -w '\\nHTTP=%{{http_code}}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{payload}'")
run("curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -d '{\"principal\":\"admin@stokr.local\",\"password\":\"x\"}' -k 2>&1 | tail -5")
run("docker logs stokr-api 2>&1 | grep -iE '403|auth/login|Access Denied|Forbidden' | tail -15")
run("docker logs stokr-caddy 2>&1 | tail -20")
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT email, enabled, deleted, locked_until, failed_login_attempts FROM auth_users WHERE email LIKE '%admin%' LIMIT 5;" """)
c.close()
