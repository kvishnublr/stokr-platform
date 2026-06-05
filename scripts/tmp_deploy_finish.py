#!/usr/bin/env python3
import paramiko
import sys
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)


def run(cmd, timeout=1800):
    print(f"$ {cmd}", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-4000:] if len(out) > 4000 else out
    sys.stdout.buffer.write(tail.encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(f"\nexit={code}\n".encode())
    sys.stdout.flush()
    return code, out


# UI may already be building; rebuild to be safe
run(f"cd {BASE} && docker compose --profile app build --no-cache ui", timeout=1800)
run("docker rm -f stokr-api stokr-ui 2>/dev/null || true")
run(f"cd {BASE} && docker compose --profile app up -d api ui")
print("Waiting 120s...", flush=True)
time.sleep(120)
for cmd in [
    f"cd {BASE} && git rev-parse HEAD && git log -1 --oneline",
    'docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"',
    "curl -s -o /dev/null -w 'actuator_health=%{http_code}\n' http://127.0.0.1:8080/actuator/health",
    "curl -s http://127.0.0.1:8080/actuator/health | head -c 300",
    "docker logs stokr-api 2>&1 | tail -25",
]:
    run(cmd, timeout=120)
c.close()
