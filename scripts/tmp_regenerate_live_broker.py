#!/usr/bin/env python3
"""Deploy regenerate fix and fire ADV_CASH LIVE signal to broker."""
import json
import time
import paramiko

HOST = "173.249.55.84"
BASE = "/opt/stokr/stokr-platform"
SOURCE = "54396dd7-890f-44bc-8a9e-58cb6076bf00"


def run(c, cmd, t=900):
    print("$", cmd[:120])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    if out:
        print(out[-4000:])
    return o.channel.recv_exit_status()


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username="root", password="Temp1234..", timeout=30)
    run(c, f"cd {BASE} && git pull origin Release_v1", 120)
    run(c, "docker rm -f stokr-api 2>/dev/null; true", 30)
    code = run(c, f"cd {BASE} && bash deploy.sh api", 1200)
    if code != 0:
        run(c, f"cd {BASE} && docker compose --profile app up -d api", 300)
    for i in range(48):
        _, o, _ = c.exec_command("curl -sf http://127.0.0.1:8080/actuator/health", timeout=15)
        if '"UP"' in o.read().decode():
            print("health UP", i)
            break
        time.sleep(10)

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
        "token = login['data']['accessToken']\n"
        f'path = "/api/admin/feed/regenerate-catalog-signal?signalId={SOURCE}&preferLive=true"\n'
        "print(post(path, None, token))\n"
        "PY"
    )
    run(c, py, 300)
    time.sleep(3)
    run(
        c,
        """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select o.id, o.execution_mode, o.state, o.signal_id, o.broker_order_id, o.reject_reason, o.created_at
from oms_orders o where o.deleted=false and o.created_at >= now() - interval '3 minutes'
order by o.created_at desc limit 6;" """,
        60,
    )
    run(
        c,
        "docker logs stokr-api 2>&1 | grep -E 'catalog_signal.regenerated|order.intent.mode_resolved|execution.live|broker_submit|ADMIN_REGENERATE' | tail -25",
        60,
    )
    c.close()


if __name__ == "__main__":
    main()
