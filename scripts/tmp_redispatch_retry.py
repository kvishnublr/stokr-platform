#!/usr/bin/env python3
"""Retry orphan redispatch after admin login issues."""
import paramiko

HOST = "173.249.55.84"
BASE = "http://127.0.0.1:8080"


def remote_run(c, cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read() + e.read()).decode("utf-8", "replace")


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username="root", password="Temp1234..", timeout=30)

    print("auth_users admin row:")
    print(
        remote_run(
            c,
            """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, principal, version, updated_at from auth_users where principal='admin';" """,
        )
    )

    _, o, e = c.exec_command(
        "python3 - <<'PY'\n"
        "import json, time, urllib.request, urllib.error\n"
        'BASE = "http://127.0.0.1:8080"\n'
        "def post(path, body=None, token=None):\n"
        "    data = json.dumps(body).encode() if body else None\n"
        '    headers = {"Content-Type": "application/json"}\n'
        "    if token:\n"
        '        headers["Authorization"] = "Bearer " + token\n'
        '    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")\n'
        "    return urllib.request.urlopen(req, timeout=300).read().decode()\n"
        "token = None\n"
        "for attempt in range(8):\n"
        "    try:\n"
        '        login = json.loads(post("/api/auth/login", {"principal": "admin", "password": "password"}))\n'
        '        token = login["data"]["accessToken"]\n'
        '        print("login ok attempt", attempt)\n'
        "        break\n"
        "    except urllib.error.HTTPError as ex:\n"
        '        print("login HTTP", ex.code, ex.read().decode()[:200])\n'
        "        time.sleep(3)\n"
        "if not token:\n"
        '    raise SystemExit("login failed")\n'
        "try:\n"
        '    out = post("/api/admin/feed/redispatch-orphan-signals", None, token)\n'
        '    print("redispatch:", out[:2500])\n'
        "except urllib.error.HTTPError as ex:\n"
        '    print("redispatch HTTP", ex.code, ex.read().decode()[:800])\n'
        "    raise\n"
        "PY",
        timeout=400,
    )
    print(o.read().decode())
    err = e.read().decode()
    if err:
        print("stderr:", err)

    print(remote_run(c, 'docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c "SELECT COUNT(*) FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE;"'))
    print(remote_run(c, 'docker logs stokr-api 2>&1 | grep -E "orphan_signal|oms.intent.sync_complete" | tail -30'))
    c.close()


if __name__ == "__main__":
    main()
