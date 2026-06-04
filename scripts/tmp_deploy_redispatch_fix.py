#!/usr/bin/env python3
"""Deploy orphan redispatch SQL fix and run redispatch on prod."""
import time

import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run_ssh(c, cmd, t=900):
    print("$", cmd)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    if out:
        print(out[-4000:] if len(out) > 4000 else out)
    return o.channel.recv_exit_status()


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    run_ssh(c, f"cd {BASE} && git pull origin Release_v1", 120)
    run_ssh(
        c,
        "docker rm -f stokr-api 2>/dev/null; "
        "docker ps -a --filter name=stokr-api --format '{{.Names}}' | xargs -r docker rm -f 2>/dev/null; true",
        60,
    )
    code = run_ssh(c, f"cd {BASE} && bash deploy.sh api", 1200)
    if code != 0:
        print("deploy.sh failed, forcing compose up")
        run_ssh(
            c,
            f"cd {BASE} && docker compose --profile app build api && docker compose --profile app up -d api",
            1200,
        )

    print("waiting for health...")
    for i in range(48):
        _, o, _ = c.exec_command("curl -sf http://127.0.0.1:8080/actuator/health", timeout=15)
        h = o.read().decode()
        if '"status":"UP"' in h or '"status": "UP"' in h:
            print("health UP at", i)
            break
        time.sleep(10)
    else:
        print("health timeout")
        run_ssh(c, "docker logs stokr-api 2>&1 | tail -40", 60)
        c.close()
        return 1

    _, o, _ = c.exec_command(f"cd {BASE} && git rev-parse --short HEAD", 30)
    print("server HEAD:", o.read().decode().strip())

    run_ssh(
        c,
        "python3 - <<'PY'\n"
        "import json, urllib.request\n"
        'BASE = "http://127.0.0.1:8080"\n'
        "def post(path, body=None, token=None):\n"
        "    data = json.dumps(body).encode() if body else None\n"
        '    headers = {"Content-Type": "application/json"}\n'
        "    if token:\n"
        '        headers["Authorization"] = "Bearer " + token\n'
        '    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")\n'
        "    return urllib.request.urlopen(req, timeout=180).read().decode()\n"
        'login = json.loads(post("/api/auth/login", {"principal": "admin", "password": "password"}))\n'
        'token = login["data"]["accessToken"]\n'
        'print(post("/api/admin/feed/redispatch-orphan-signals", None, token))\n'
        "PY",
        200,
    )

    run_ssh(
        c,
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c '
        '"SELECT COUNT(*) FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE;"',
        60,
    )
    run_ssh(
        c,
        'docker logs stokr-api 2>&1 | grep -E "orphan|redispatch|oms.intent" | tail -25',
        60,
    )
    c.close()
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
