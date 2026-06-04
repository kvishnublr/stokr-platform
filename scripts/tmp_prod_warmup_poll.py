import paramiko, json, time

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
PG = "docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)

def run(cmd, t=60):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read() + e.read()).decode("utf-8", "replace").strip()

def login():
    raw = run('curl -sf -m 20 -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -d \'{"principal":"admin","password":"password"}\'')
    return json.loads(raw)["data"]["accessToken"]

token = login()
for i in range(3):
    raw = run('curl -sf -m 25 -H "Authorization: Bearer %s" "http://127.0.0.1:8080/api/admin/operations/diagnostics"' % token)
    d = json.loads(raw)["data"]
    ss = d.get("safeStartup", {})
    print("poll", i, "safeStartup=", ss)
    if ss.get("ready"):
        break
    time.sleep(45)

raw = run('curl -sf -m 25 -H "Authorization: Bearer %s" "http://127.0.0.1:8080/api/admin/readiness"' % token)
print("readiness:", raw[:2500])

print(run(PG + " -c \"SELECT sd.strategy_key, si.id, si.user_id, si.enabled, si.execution_mode, si.runtime_state FROM strategy_instances si JOIN strategy_definitions sd ON sd.id = si.definition_id WHERE sd.strategy_key = 'ADV_CASH' ORDER BY si.updated_at DESC LIMIT 5\""))

print(run(PG + " -c \"\\d strategy_signals\" 2>&1 | head -25"))

print(run(PG + " -c \"SELECT id, created_at AT TIME ZONE 'Asia/Kolkata' FROM strategy_signals ORDER BY created_at DESC LIMIT 3\""))

# grep code on server
print(run("grep -r \"SAFE_STARTUP\" /opt/stokr/stokr-platform/modules --include=\"*.java\" 2>/dev/null | head -15"))
print(run("grep -r \"resolveCatalogDispatchUserId\" /opt/stokr/stokr-platform/modules --include=\"*.java\" 2>/dev/null | head -8"))

c.close()
