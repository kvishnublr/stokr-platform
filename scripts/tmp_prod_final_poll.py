import paramiko, json
HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
PG = "docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)

def run(cmd, t=90):
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace").strip()
    print(out[-8000:])
    return out

tok = json.loads(run('curl -sf -m 20 -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -d \'{"principal":"admin","password":"password"}\''))["data"]["accessToken"]

for ep in ["operations/diagnostics", "readiness", "operations/snapshot"]:
    raw = run('curl -sf -m 30 -H "Authorization: Bearer %s" "http://127.0.0.1:8080/api/admin/%s"' % (tok, ep))
    if raw:
        d = json.loads(raw).get("data", {})
        if ep == "operations/diagnostics":
            print("SAFE:", d.get("safeStartup"))
            print("ADV mode:", (d.get("strategyModes") or {}).get("ADV_CASH"))
        if ep == "operations/snapshot":
            sys = d.get("system") or {}
            print("SNAPSHOT kill=%s liveArmed=%s" % (sys.get("killSwitch"), sys.get("liveTradingArmed")))

run(PG + " -c \"SELECT sd.strategy_key, si.user_id, si.enabled, si.execution_mode, si.runtime_state FROM strategy_instances si JOIN strategy_definitions sd ON sd.id = si.definition_id WHERE sd.strategy_key = 'ADV_CASH' LIMIT 5\"")

run("grep -r \"SAFE_STARTUP_NOT_READY\\|safeStartup\" /opt/stokr/stokr-platform/modules/strategy-signals --include=\"*.java\" 2>/dev/null | head -12")
run("grep -r \"resolveCatalogDispatchUserId\" /opt/stokr/stokr-platform/modules --include=\"*.java\" 2>/dev/null | head -6")

c.close()
