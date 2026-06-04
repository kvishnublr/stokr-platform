#!/usr/bin/env python3
import json
import paramiko
import urllib.request

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=120):
    print("\n$", cmd[:200])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-6000:] if len(out) > 6000 else out)
    return out


run("date -u; TZ=Asia/Kolkata date")
run(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT strategy_name, count(*) cnt, max(created_at) last_utc,
       max(created_at AT TIME ZONE 'Asia/Kolkata') last_ist
FROM strategy_signals
WHERE created_at >= '2026-06-04'::date AND deleted=false AND is_test_trade=false
GROUP BY strategy_name ORDER BY last_ist DESC NULLS LAST;
" """
)
run(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT created_at AT TIME ZONE 'Asia/Kolkata' ist, strategy_name, symbol, pipeline, outcome_status
FROM strategy_signals
WHERE created_at >= '2026-06-04 06:30:00+00' AND deleted=false AND test_trade=false
ORDER BY created_at DESC LIMIT 25;
" """
)
run(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT sd.strategy_key, si.runtime_state, si.enabled, si.execution_mode, u.username
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id=si.definition_id
LEFT JOIN users u ON u.id=si.user_id
WHERE sd.strategy_key IN ('INDEX_HUNT','ADV_CASH') ORDER BY sd.strategy_key;
" """
)
run("docker logs stokr-api --since 6h 2>&1 | grep -iE 'catalog.scan|INDEX_HUNT|scan.blocked|integrity_blocked|daily_cap|FEED_STALE|safe.startup|STARTUP_WARMUP|no_active_bindings|signals=' | tail -80")
run("docker logs stokr-api --since 6h 2>&1 | grep -iE 'signal.dropped|persist_failed|SESSION_BLOCKED|COOLDOWN' | tail -40")
run(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT strategy_key, scan_attempts, signals_generated, last_scan_at, last_signal_at, blocked_reason
FROM strategy_runtime_health WHERE strategy_key IN ('INDEX_HUNT','ADV_CASH')
ORDER BY strategy_key;
" """
)
# admin diagnostics via localhost
py = r'''python3 - <<'PY'
import json, urllib.request
BASE = "http://127.0.0.1:8080"
def post(path, body=None, token=None):
    data = json.dumps(body).encode() if body else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    return urllib.request.urlopen(req, timeout=60).read().decode()
login = json.loads(post("/api/auth/login", {"principal": "admin", "password": "password"}))
token = login["data"]["accessToken"]
req = urllib.request.Request(BASE + "/api/admin/operations/diagnostics", headers={"Authorization": "Bearer " + token})
print(req.read().decode()[:4000] if False else urllib.request.urlopen(req, timeout=60).read().decode()[:5000])
PY'''
run(py, t=90)
c.close()
