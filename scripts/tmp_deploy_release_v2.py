#!/usr/bin/env python3
"""Deploy Release_v1 fixes to production."""
import paramiko
import sys
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
EXPECTED = "b38b0381"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)


def run(cmd, timeout=1800, label=""):
    print(f"\n{'='*60}\n$ {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-5000:] if len(out) > 5000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    return code, out


steps = [
    f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1",
    f"cd {BASE} && git rev-parse --short HEAD",
    f"cd {BASE} && docker compose --profile app build --no-cache api",
    f"cd {BASE} && docker compose --profile app build --no-cache ui",
    "docker rm -f stokr-api stokr-ui 2>/dev/null || true",
    f"cd {BASE} && docker compose --profile app up -d api ui",
]

for cmd in steps:
    code, out = run(cmd)
    if code != 0 and "git rev-parse" not in cmd and "docker rm" not in cmd:
        print("FAILED:", cmd, file=sys.stderr)
        sys.exit(1)

print("Waiting 120s for API startup...", flush=True)
time.sleep(120)

verify = [
    f"cd {BASE} && git rev-parse HEAD && git log -1 --oneline",
    'docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"',
    "curl -s -o /dev/null -w 'actuator_health=%{http_code}\n' http://127.0.0.1:8080/actuator/health",
    "curl -s http://127.0.0.1:8080/actuator/health | head -c 500",
    "docker logs stokr-api 2>&1 | tail -40",
    f"docker exec stokr-api jar tf /app/app.jar 2>/dev/null | grep -E 'V001__|V002__|V011__|AdminDiagnostics' | head -10 || true",
]

for cmd in verify:
    run(cmd, timeout=120)

c.close()
print("\nDEPLOY COMPLETE", flush=True)
