#!/usr/bin/env python3
import paramiko, sys, time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=1800):
    print(f"$ {cmd}", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-2500:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(f"\nexit={code}\n\n".encode())
    sys.stdout.flush()
    return code, out


run(f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1")
run(f"cd {BASE} && git rev-parse --short HEAD && git log -1 --oneline")
run(f"cd {BASE} && docker compose --profile app build --no-cache api")
run("docker rm -f stokr-api 2>/dev/null || true")
run(f"cd {BASE} && docker compose --profile app up -d api")

health = "000"
for i in range(20):
    time.sleep(30)
    _, out = run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health", timeout=30)
    if "health=200" in out:
        health = "200"
        break
    health = out.strip().split("health=")[-1] if "health=" in out else "000"

run('docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"')
run("curl -s http://127.0.0.1:8080/actuator/health | head -c 400")
run("docker logs stokr-api 2>&1 | grep -iE 'Started StokrApplication|Application run failed|Schema-validation' | tail -5")
# start ui without health dependency if needed
run("docker rm -f stokr-ui 2>/dev/null || true")
run(f"cd {BASE} && docker compose --profile app up -d --no-deps ui")
run('docker ps --filter name=stokr-ui --format "{{.Names}} {{.Status}}"')

print(f"\nFINAL health={health}", flush=True)
c.close()
