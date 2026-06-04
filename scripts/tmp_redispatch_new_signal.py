#!/usr/bin/env python3
import paramiko

SIGNAL = "53f8ca95-6e7d-459a-ba20-fe429c09812e"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

# Redispatch via inline OMS for this specific signal
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
    # regenerate again from same ADV_CASH source with preferLive\n"
    'out = post("/api/admin/feed/regenerate-catalog-signal?signalId=54396dd7-890f-44bc-8a9e-58cb6076bf00&preferLive=true", None, token)\n'
    "print(out)\n"
    "PY"
)
_, o, e = c.exec_command(py, timeout=300)
print(o.read().decode())
_, o, e = c.exec_command(
    f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, execution_mode, state, signal_id, broker_order_id from oms_orders
where created_at >= now() - interval '5 minutes' and deleted=false order by created_at desc limit 8;" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
_, o, e = c.exec_command(
    "docker logs stokr-api 2>&1 | grep -E 'execution\\.live|broker_submit|both_mode|oms.intent' | tail -20",
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
