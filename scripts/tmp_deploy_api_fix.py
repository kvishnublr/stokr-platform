#!/usr/bin/env python3
import paramiko, time, sys
HOST, BASE = "173.249.55.84", "/opt/stokr/stokr-platform"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, t=1800):
    print(f"\n$ {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    print(out[-3500:], flush=True)
    print(f"exit={code}", flush=True)
    return code

run(f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1")
code = run(f"cd {BASE} && bash deploy.sh api", t=1800)
if code != 0:
    run("docker rm -f stokr-api 2>/dev/null || true")
    code = run(f"cd {BASE} && docker compose --profile app up -d --build api", t=1800)

print("Waiting 120s for startup...", flush=True)
time.sleep(120)

for i in range(12):
    hc = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health")
    if "200" in hc[1]:
        break
    time.sleep(15)

run('docker ps --filter name=stokr-api --format "{{.Names}} {{.Status}}"')
run("docker logs stokr-api 2>&1 | tail -40")
run("docker exec stokr-api jar tf /app/stokr-bootstrap.jar | grep -E 'AdminDiagnostics|AdminDashboard|io/stokr/bootstrap/controller' | head -10")
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version,description,success FROM flyway_schema_history WHERE version IN ('98','99');" """)
code_ui = run(f"cd {BASE} && bash deploy.sh ui", t=1200)
if code_ui != 0:
    run("docker rm -f stokr-ui 2>/dev/null || true")
    run(f"cd {BASE} && docker compose --profile app up -d ui")
c.close()
print("\nDONE", flush=True)
