#!/usr/bin/env python3
"""Deploy Telegram OAuth alerts to prod."""
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
    tail = out[-6000:] if len(out) > 6000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    if code != 0:
        raise SystemExit(code)
    return out

run(f"cd {MAIN} && git fetch origin Release_v2 && git reset --hard origin/Release_v2 && git log -1 --oneline")

# Ensure Telegram operator alerts enabled on prod
run(
    f"""bash -lc 'cd {MAIN} && upsert() {{ k="$1"; v="$2"; grep -q "^$k=" .env 2>/dev/null && sed -i "s|^$k=.*|$k=$v|" .env || echo "$k=$v" >> .env; }}; \
upsert STOKR_TELEGRAM_DRY_RUN false; \
grep -E "^STOKR_TELEGRAM_(OPERATOR_CHAT_ID|DRY_RUN|BOT_TOKEN)=" .env | sed "s/BOT_TOKEN=.*/BOT_TOKEN=***/"'"""
)

run(f"cd {MAIN} && export STOKR_DEPLOY_BRANCH=Release_v2 && bash deploy.sh api", timeout=3600)
print("Waiting 60s...", flush=True)
time.sleep(60)
run("curl -sf http://127.0.0.1:8080/actuator/health | head -c 200")
c.close()
print("Done", flush=True)
