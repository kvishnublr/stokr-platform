#!/usr/bin/env python3
import paramiko

SOURCE = "54396dd7-890f-44bc-8a9e-58cb6076bf00"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

cmds = [
    "docker exec stokr-redis redis-cli GET stokr:strategy:ADV_CASH:enabled",
    "docker exec stokr-redis redis-cli SET stokr:strategy:ADV_CASH:enabled 1",
    "docker exec stokr-redis redis-cli GET stokr:strategy:ADV_CASH:enabled",
]
for cmd in cmds:
    print("$", cmd)
    _, o, e = c.exec_command(cmd, timeout=30)
    print((o.read() + e.read()).decode().strip())

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
    f'print(post("/api/admin/feed/regenerate-catalog-signal?signalId={SOURCE}&preferLive=true", None, token))\n'
    "PY"
)
print("$ regenerate")
_, o, e = c.exec_command(py, timeout=300)
print(o.read().decode())

_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c "
select execution_mode, state, broker_order_id, reject_reason from oms_orders
where deleted=false and created_at >= now() - interval '2 minutes' order by created_at desc limit 3;" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
_, o, e = c.exec_command(
    "docker logs stokr-api 2>&1 | grep -E 'be71d838|execution.live|broker_submit|mode_resolved|Strategy disabled' | tail -15",
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
