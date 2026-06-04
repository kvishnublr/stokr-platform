import paramiko, json

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
PG = "docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"

def run(c, cmd, t=120):
    print("\n>>>", cmd[:220])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace").strip()
    if out: print(out[-12000:])
    return out

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)

tok_raw = run(c, 'curl -sf -m 20 -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -d \'{"principal":"admin","password":"password"}\'')
token = json.loads(tok_raw).get("data", {}).get("accessToken", "")

for ep in ["operations/diagnostics", "readiness", "operations/snapshot", "oms/diagnostics"]:
    raw = run(c, 'curl -sf -m 25 -H "Authorization: Bearer %s" "http://127.0.0.1:8080/api/admin/%s"' % (token, ep))
    if raw:
        try:
            data = json.loads(raw).get("data", json.loads(raw))
            print("  JSON snippet:", json.dumps(data)[:3500])
        except Exception as ex:
            print("  parse err", ex)

for k in ["stokr:kill_switch", "stokr:kill:switch", "stokr:live_trading_armed", "stokr:live:armed", "stokr:strategy:ADV_CASH:enabled"]:
    run(c, "docker exec stokr-redis redis-cli GET " + k)

run(c, PG + " -c \"SELECT column_name FROM information_schema.columns WHERE table_name='strategy_instances' ORDER BY 1\"")

run(c, PG + " -c \"SELECT sd.strategy_key, si.id, si.user_id, si.state FROM strategy_instances si JOIN strategy_definitions sd ON sd.id = si.definition_id WHERE sd.strategy_key = 'ADV_CASH' ORDER BY si.updated_at DESC NULLS LAST LIMIT 5\"")

run(c, PG + " -c \"SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name ILIKE '%execution%' ORDER BY 1\"")

run(c, PG + " -c \"SELECT * FROM strategy_execution_modes LIMIT 20\"")

run(c, PG + " -c \"SELECT id, strategy_key, created_at AT TIME ZONE 'Asia/Kolkata' FROM strategy_signals WHERE strategy_key='ADV_CASH' AND created_at > now() - interval '3 days' ORDER BY created_at DESC LIMIT 8\"")

run(c, PG + " -c \"SELECT id, execution_mode, state, (broker_external_order_id IS NOT NULL) AS has_broker, left(symbol,12), created_at AT TIME ZONE 'Asia/Kolkata' FROM oms_orders WHERE strategy_key='ADV_CASH' AND execution_mode='LIVE' AND created_at > now() - interval '3 days' ORDER BY created_at DESC LIMIT 12\"")

run(c, PG + " -c \"SELECT block_code, effective_mode, count(*) FROM oms_safety_blocked_orders WHERE created_at > now() - interval '3 days' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 15\"")

run(c, "docker logs stokr-api 2>&1 | grep -iE \"SAFE_STARTUP|safe_startup|SAFE_STARTUP_NOT_READY|catalog.dispatch|downgraded|PAPER\" | tail -40")

c.close()
