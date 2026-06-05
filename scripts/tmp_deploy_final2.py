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
    sys.stdout.buffer.write(out[-1500:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(f"\nexit={code}\n\n".encode())
    return code, out

run(f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1")
run(f"cd {BASE} && git rev-parse --short HEAD && git log -1 --oneline")
run(f"cd {BASE} && docker compose --profile app build --no-cache api")
run("docker rm -f stokr-api 2>/dev/null || true")
run(f"cd {BASE} && docker compose --profile app up -d api")
health = "000"
for _ in range(30):
    time.sleep(20)
    _, out = run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health", timeout=30)
    if "health=200" in out:
        health = "200"
        break
run('docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"')
run("curl -s http://127.0.0.1:8080/actuator/health")
run("docker logs stokr-api 2>&1 | grep 'Started StokrApplication' | tail -1")
print(f"FINAL health={health}", flush=True)
c.close()
