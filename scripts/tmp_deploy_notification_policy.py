#!/usr/bin/env python3
import json, time, urllib.request, paramiko

HOST = "173.249.55.84"
MAIN = "/opt/stokr/stokr-platform"
BASE = f"http://{HOST}:8080"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=3600):
    print(f"\n=== {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    print(out[-4000:], flush=True)
    print("exit=", code, flush=True)
    if code != 0:
        raise SystemExit(code)

run(f"cd {MAIN} && git fetch origin Release_v2 && git reset --hard origin/Release_v2 && git log -1 --oneline")

run(
    f"""bash -lc 'cd {MAIN} && upsert() {{ k="$1"; v="$2"; grep -q "^$k=" .env 2>/dev/null && sed -i "s|^$k=.*|$k=$v|" .env || echo "$k=$v" >> .env; }}; \
upsert STOKR_ZERODHA_OAUTH_RECONNECT_ALERT_ENABLED false; \
upsert STOKR_ZERODHA_OAUTH_SUCCESS_ALERT_ENABLED true; \
upsert STOKR_TELEGRAM_OPERATOR_ALERTS_ENABLED true; \
upsert STOKR_TELEGRAM_TRADER_FILL_ALERTS_ENABLED true; \
upsert STOKR_TELEGRAM_OPERATOR_READINESS_ALERTS_ENABLED true; \
upsert STOKR_TELEGRAM_BOT_USERNAME STOKR_SIGNAL_BOT; \
upsert STOKR_TELEGRAM_DRY_RUN false; \
grep -E "^STOKR_(TELEGRAM|ZERODHA_OAUTH)" .env | sed "s/BOT_TOKEN=.*/BOT_TOKEN=***/"'"""
)

run(f"cd {MAIN} && export STOKR_DEPLOY_BRANCH=Release_v2 && bash deploy.sh api", timeout=3600)
print("waiting 70s...", flush=True)
time.sleep(70)

login = json.dumps({"principal": "admin@stokr.local", "password": "admin123"}).encode()
token = json.load(urllib.request.urlopen(
    urllib.request.Request(BASE + "/api/auth/login", data=login, headers={"Content-Type": "application/json"}),
    timeout=20,
))["data"]["accessToken"]

pre = json.load(urllib.request.urlopen(
    urllib.request.Request(
        BASE + "/api/admin/operations/automation/pre-open",
        data=b"",
        method="POST",
        headers={"Authorization": "Bearer " + token},
    ),
    timeout=120,
))
print("pre-open result:", json.dumps(pre.get("data", pre), indent=2)[:800])

c.close()
print("done", flush=True)
