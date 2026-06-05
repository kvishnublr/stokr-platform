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
    sys.stdout.buffer.write(out[-3000:].encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(f"\nexit={code}\n\n".encode())
    sys.stdout.flush()
    return code, out


for cmd in [
    f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1",
    f"cd {BASE} && git rev-parse --short HEAD",
    f"cd {BASE} && docker compose --profile app build --no-cache api",
    "docker rm -f stokr-api 2>/dev/null || true",
    f"cd {BASE} && docker compose --profile app up -d api",
    "docker rm -f stokr-ui 2>/dev/null || true",
    f"cd {BASE} && docker compose --profile app up -d ui",
]:
    code, _ = run(cmd)
    if code != 0 and "docker rm" not in cmd:
        sys.exit(1)

for i in range(16):
    time.sleep(30)
    code, out = run("curl -s -o /dev/null -w 'health=%{http_code}' http://127.0.0.1:8080/actuator/health", timeout=30)
    if "health=200" in out:
        break

run(f"cd {BASE} && git rev-parse HEAD && git log -1 --oneline")
run('docker ps --filter name=stokr-api --filter name=stokr-ui --format "{{.Names}} {{.Status}}"')
run("curl -s http://127.0.0.1:8080/actuator/health | head -c 500")
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version, description, success FROM flyway_schema_history WHERE version::int >= 98 ORDER BY installed_rank;" """)
c.close()
