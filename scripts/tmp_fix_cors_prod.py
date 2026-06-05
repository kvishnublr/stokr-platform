#!/usr/bin/env python3
"""Fix prod CORS by recreating API with .env values."""
import paramiko
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=120):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:2500]}\n")
    return out

BASE = "/opt/stokr/stokr-platform"
run(f"grep STOKR_CORS {BASE}/.env")
run(f"cd {BASE} && docker compose --profile app up -d --force-recreate api")
print("Waiting 90s...", flush=True)
time.sleep(90)
run("docker exec stokr-api printenv | grep STOKR_CORS")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -H 'Origin: https://stokr.in' -d '{"principal":"admin@stokr.local","password":"admin123"}'""")
run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health")
c.close()
print("CORS FIX DONE")
