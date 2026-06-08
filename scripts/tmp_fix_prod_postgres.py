#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
PWD = "Temp1234.."
MAIN = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=600):
    print(f"\n>>> {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-15000:] if len(out) > 15000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    return code, out


# Inspect volumes and rogue postgres
run("docker volume ls | grep -i stokr")
run("docker inspect stokr-postgres --format 'Image={{.Config.Image}} Mounts={{json .Mounts}}'")
run(f"grep -A20 '^  postgres:' {MAIN}/docker-compose.yml 2>/dev/null | head -25")

# Fix: remove rogue postgres, start via compose
run("docker stop stokr-postgres && docker rm stokr-postgres")
run(f"cd {MAIN} && docker compose --profile app up -d postgres")
print("Waiting 15s for postgres...", flush=True)
time.sleep(15)
run("docker inspect stokr-postgres --format 'Network={{json .NetworkSettings.Networks}}'")
run(f"cd {MAIN} && docker compose --profile app up -d redis rabbitmq autoheal")
run(f"cd {MAIN} && docker compose --profile app --profile v2-ui up -d --no-deps --force-recreate api")
print("Waiting 90s for API...", flush=True)
time.sleep(90)
run("curl -sf http://127.0.0.1:8080/actuator/health | head -c 300")
run("curl -sf https://stokr.in/api/actuator/health | head -c 300")
run("docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -c \"SELECT count(*) FROM strategy_runtime_bindings;\" 2>&1 || docker exec stokr-postgres env | grep POSTGRES")

c.close()
