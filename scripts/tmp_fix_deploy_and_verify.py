#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
PWD = "Temp1234.."
MAIN = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=3600):
    print(f"\n=== {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-10000:] if len(out) > 10000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    if code != 0:
        raise SystemExit(code)
    return out


# Remove stale compose containers blocking recreate
run("docker ps -a --filter name=stokr-api --format '{{.ID}} {{.Names}} {{.Status}}'")
run("docker rm -f fb31ce053560 2>/dev/null || true")
run("docker ps -a --filter name=stokr-api --format '{{.ID}} {{.Names}} {{.Status}}'")

run(f"cd {MAIN} && export STOKR_DEPLOY_BRANCH=Release_v2 && bash deploy.sh api", timeout=3600)
print("Waiting 90s for API startup...", flush=True)
time.sleep(90)

run("curl -sf http://127.0.0.1:8080/actuator/health")
run('curl -sI "https://stokr.in/api/broker/zerodha/callback" | head -20')
run('curl -s "https://stokr.in/api/broker/zerodha/callback?zerodha=error&reason=missing_params" | head -c 500')
run("docker logs stokr-api --since 30m 2>&1 | grep -i 'zerodha.callback' | tail -30 || true")
run("docker logs stokr-api --since 30m 2>&1 | grep -i 'platform_feed' | tail -20 || true")

c.close()
print("Done", flush=True)
