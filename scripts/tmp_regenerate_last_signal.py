#!/usr/bin/env python3
"""Deploy and regenerate last catalog signal (ADV_CASH LIVE) on prod."""
import json
import time

import paramiko

HOST = "173.249.55.84"
BASE = "/opt/stokr/stokr-platform"
# Last morning ADV_CASH catalog signal (HINDUNILVR BUY)
ADV_CASH_SIGNAL = "54396dd7-890f-44bc-8a9e-58cb6076bf00"


def run(c, cmd, t=900):
    print("$", cmd[:140])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    if out:
        print(out[-5000:] if len(out) > 5000 else out)
    return o.channel.recv_exit_status()


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username="root", password="Temp1234..", timeout=30)

    run(c, f"cd {BASE} && git pull origin Release_v1", 120)
    run(c, "docker rm -f stokr-api 7c95ff535e99_stokr-api 2>/dev/null; true", 30)
    code = run(c, f"cd {BASE} && bash deploy.sh api", 1200)
    if code != 0:
        run(
            c,
            f"cd {BASE} && docker compose --profile app build api && docker compose --profile app up -d api",
            1200,
        )

    for i in range(48):
        _, o, _ = c.exec_command("curl -sf http://127.0.0.1:8080/actuator/health", timeout=15)
        if '"UP"' in o.read().decode():
            print("health UP", i)
            break
        time.sleep(10)

    run(
        c,
        """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, strategy_name, symbol, signal_type, created_at
from strategy_signals
where deleted=false and is_test_trade=false
order by created_at desc limit 3;" """,
        60,
    )

    py = (
        "python3 - <<'PY'\n"
        "import json, urllib.request\n"
        'BASE = "http://127.0.0.1:8080"\n'
        "def post(path, body=None, token=None):\n"
        "    data = json.dumps(body).encode() if body else None\n"
        '    headers = {"Content-Type": "application/json"}\n'
        "    if token:\n"
        '        headers["Authorization"] = "Bearer " + token\n'
        '    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")\n'
        "    return urllib.request.urlopen(req, timeout=300).read().decode()\n"
        'login = json.loads(post("/api/auth/login", {"principal": "admin", "password": "password"}))\n'
        'token = login["data"]["accessToken"]\n'
        f'path = "/api/admin/feed/regenerate-catalog-signal?signalId={ADV_CASH_SIGNAL}&preferLive=true"\n'
        "print(post(path, None, token))\n"
        "PY"
    )
    run(c, py, 300)

    run(
        c,
        f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select o.id, o.signal_id, o.execution_mode, o.state, o.broker_order_id, o.created_at
from oms_orders o
where o.deleted=false and (o.signal_id = '{ADV_CASH_SIGNAL}'::uuid
   or o.created_at >= now() - interval '10 minutes')
order by o.created_at desc limit 10;" """,
        60,
    )

    run(
        c,
        'docker logs stokr-api 2>&1 | grep -E "catalog_signal.regenerated|order.intent|broker|place_order|ZERODHA|execution.dispatch" | tail -35',
        60,
    )
    c.close()


if __name__ == "__main__":
    main()
