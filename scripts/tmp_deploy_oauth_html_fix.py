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
    tail = out[-8000:] if len(out) > 8000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    if code != 0:
        raise SystemExit(code)
    return out


run(f"cd {MAIN} && git fetch origin Release_v2 && git reset --hard origin/Release_v2 && git log -1 --oneline")
run(f"cd {MAIN} && export STOKR_DEPLOY_BRANCH=Release_v2 && bash deploy.sh api", timeout=3600)
print("Waiting 75s...", flush=True)
time.sleep(75)
run("curl -sf http://127.0.0.1:8080/actuator/health | head -c 200")
run('curl -s "http://127.0.0.1:8080/api/broker/zerodha/callback?status=error&state=00000000-0000-0000-0000-000000000000" | head -c 300')
c.close()
print("Done", flush=True)
