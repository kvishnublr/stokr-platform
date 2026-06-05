#!/usr/bin/env python3
import json
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=30)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out.strip()[:1500])
    return out

login = json.loads(run(
    "curl -s -X POST https://stokr.in/api/auth/login "
    "-H 'Content-Type: application/json' "
    "-d '{\"principal\":\"admin\",\"password\":\"admin123\"}'"
))
token = login["data"]["accessToken"]
print("\nWith valid Bearer + wrong password on login:")
run(
    "curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login "
    "-H 'Content-Type: application/json' "
    f"-H 'Authorization: Bearer {token}' "
    "-d '{\"principal\":\"admin@stokr.local\",\"password\":\"wrongpass\"}'"
)
print("\nWith valid Bearer + correct password on login:")
run(
    "curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login "
    "-H 'Content-Type: application/json' "
    f"-H 'Authorization: Bearer {token}' "
    "-d '{\"principal\":\"admin@stokr.local\",\"password\":\"admin123\"}'"
)
c.close()
