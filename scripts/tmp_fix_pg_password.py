#!/usr/bin/env python3
"""Fix postgres/API password mismatch after accidental postgres recreate."""
import paramiko
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=120):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:3000]}\n")
    return out

BASE = "/opt/stokr/stokr-platform"
run(f"grep -E 'POSTGRES_PASSWORD|SPRING_DATASOURCE_PASSWORD' {BASE}/.env | sed 's/=.*/=***masked***/'")

# Resolve password API expects
_, o, e = c.exec_command(
    f"bash -lc 'set -a; source {BASE}/.env; echo ${{SPRING_DATASOURCE_PASSWORD:-$POSTGRES_PASSWORD}}'",
    timeout=30,
)
target_pass = (o.read() + e.read()).decode().strip().splitlines()[-1]
escaped = target_pass.replace("'", "''")

run(f"""docker exec stokr-postgres psql -U postgres -d postgres -c "ALTER USER postgres WITH PASSWORD '{escaped}';" """)
run(f"cd {BASE} && docker compose --profile app up -d --force-recreate --no-deps api")
print("Waiting 120s for API...", flush=True)
time.sleep(120)
run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health")
run("docker exec stokr-api printenv | grep STOKR_CORS")
run("""curl -s -w '\\nHTTP=%{http_code}' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -H 'Origin: https://stokr.in' -d '{"principal":"admin@stokr.local","password":"admin123"}' | tail -c 400""")
c.close()
print("DONE")
