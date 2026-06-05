#!/usr/bin/env python3
"""Deploy API + UI to prod Release_v1."""
import paramiko
import time
import sys

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)

def run(cmd, timeout=1200, label=""):
    print(f"\n{'='*60}\n$ {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-4000:] if len(out) > 4000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    return code, out

# Pull latest
code, out = run(f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1")
if code != 0:
    sys.exit(1)
print("HEAD:", run(f"cd {BASE} && git log -1 --oneline")[1].split("\n")[0])

# API deploy
code, _ = run(f"cd {BASE} && bash deploy.sh api", timeout=1800)
if code != 0:
    print("API deploy failed — trying container recreate...", flush=True)
    run("docker rm -f stokr-api 2>/dev/null || true")
    code, _ = run(f"cd {BASE} && docker compose --profile app up -d --build api", timeout=1800)

print("Waiting 90s for API startup...", flush=True)
time.sleep(90)

# UI deploy
code_ui, _ = run(f"cd {BASE} && bash deploy.sh ui", timeout=1800)
if code_ui != 0:
    run("docker rm -f stokr-ui 2>/dev/null || true")
    run(f"cd {BASE} && docker compose --profile app up -d --build ui", timeout=1800)

# Verification
run("docker compose -f /opt/stokr/stokr-platform/docker-compose.yml --profile app ps api ui 2>/dev/null || docker ps --filter name=stokr-api --filter name=stokr-ui")
run("curl -s -o /dev/null -w 'actuator_health=%{http_code}\n' http://127.0.0.1:8080/actuator/health")
run("docker exec stokr-api jar tf /app/app.jar 2>/dev/null | grep -E 'AdminDiagnostics|AdminDashboard|io/stokr/bootstrap' | head -15")
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version, description FROM flyway_schema_history WHERE version IN ('98','99') ORDER BY version;" """)
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT table_name FROM information_schema.tables WHERE table_name IN ('redis_health_log','market_data_staleness_log') ORDER BY 1;" """)
run("docker logs stokr-api 2>&1 | tail -30")

c.close()
print("\nDEPLOY RUN COMPLETE", flush=True)
