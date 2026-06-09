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

for ep in [
    "/api/admin/ops/status",
    "/api/admin/readiness",
    "/api/admin/oms/summary",
    "/api/admin/audit?page=0&size=3",
    "/api/admin/alerts",
    "/api/admin/strategies?size=5",
    "/api/admin/operations/snapshot",
    "/api/admin/settings/summary",
    "/api/health",
]:
    b = run(f"curl -s 'http://127.0.0.1:8080{ep}' -H 'Authorization: Bearer {AT}'")
    print("===", ep, "===")
    print(b[:1500])
    print()

c.close()
