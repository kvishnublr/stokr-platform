#!/usr/bin/env python3
"""Hard reset prod to af8814f + git clean + rebuild API."""
import paramiko, time
HOST, BASE = "173.249.55.84", "/opt/stokr/stokr-platform"
GOOD = "af8814f"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, t=2400):
    print(f"\n$ {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    print(out[-3000:], flush=True)
    print(f"exit={code}", flush=True)
    return code

run(f"cd {BASE} && git fetch origin && git reset --hard {GOOD} && git clean -fd")
run(f"ls {BASE}/stokr-oms/src/main/resources/db/migration 2>&1 | head -5")
run(f"cd {BASE} && docker compose --profile app build --no-cache api", t=2400)
run("docker rm -f stokr-api 2>/dev/null || true")
run(f"cd {BASE} && docker compose --profile app up -d api")
print("Waiting 180s...", flush=True)
time.sleep(180)
run("curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health")
run('docker ps --filter name=stokr-api --format "{{.Names}} {{.Status}}"')
run("docker rm -f stokr-ui 2>/dev/null; cd /opt/stokr/stokr-platform && docker compose --profile app up -d ui")
c.close()
print("RESTORE DONE", flush=True)
