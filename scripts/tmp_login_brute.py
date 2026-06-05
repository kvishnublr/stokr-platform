#!/usr/bin/env python3
import json
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

tests = [
    ("admin", "password"),
    ("admin", "admin123"),
    ("admin@stokr.local", "password"),
    ("admin@stokr.local", "admin123"),
    ("admin@stokr.local", "Temp1234.."),
]

for principal, password in tests:
    body = json.dumps({"principal": principal, "password": password})
    cmd = (
        "curl -s -w '\\nHTTP=%{http_code}' -X POST https://stokr.in/api/auth/login "
        f"-H 'Content-Type: application/json' -d '{body}'"
    )
    _, o, e = c.exec_command(cmd, timeout=30)
    out = (o.read() + e.read()).decode("utf-8", "replace").strip()
    print(f"\n{principal} / {password}\n{out[-300:]}\n")

c.close()
