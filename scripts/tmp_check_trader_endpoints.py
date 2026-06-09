#!/usr/bin/env python3
import json, paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode()

AT = json.loads(run(
    "curl -s -X POST http://127.0.0.1:8080/api/auth/login "
    "-H 'Content-Type: application/json' "
    "-d '{\"principal\":\"admin@stokr.local\",\"password\":\"admin123\"}'"
))["data"]["accessToken"]

# admin can access trader endpoints? probably not - check auth/me for admin
for ep in ["/api/auth/me", "/api/trader/terminal/workstation", "/api/portfolio/dashboard?equityPoints=5"]:
    b = run(f"curl -s -w '\\nHTTP:%{{http_code}}' 'http://127.0.0.1:8080{ep}' -H 'Authorization: Bearer {AT}'")
    print("=== ADMIN token on", ep, "===")
    print(b[:2000])

c.close()
