#!/usr/bin/env python3
import json
import paramiko

HOST = "173.249.55.84"
PWD = "Temp1234.."

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=60):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    return o.channel.recv_exit_status(), out


checks = []

# Time
_, out = run("TZ=Asia/Kolkata date")
checks.append(("time", out.strip()))

# Containers
_, out = run("docker ps --format '{{.Names}}|{{.Status}}' | grep stokr")
checks.append(("containers", out))

# API health
code, out = run("curl -sf http://127.0.0.1:8080/actuator/health")
checks.append(("api_health_code", str(code)))
try:
    checks.append(("api_health", json.loads(out)))
except Exception:
    checks.append(("api_health_raw", out[:500]))

# UI via Caddy
for url in ["https://stokr.in/", "https://stokr.in/api/actuator/health", "https://new.stokr.in/"]:
    code, out = run(f'curl -sI "{url}" | head -8')
    checks.append((f"ui_{url}", f"code_check:\n{out}"))

# Public login page
code, out = run('curl -sf -o /dev/null -w "%{http_code}" https://stokr.in/login')
checks.append(("login_http", out))

# Zerodha feed
sql = """SELECT connection_state, websocket_state, access_token_enc IS NOT NULL, updated_at
FROM platform_broker_feed_sessions WHERE deleted=false LIMIT 1"""
_, out = run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -c "{sql}"')
checks.append(("zerodha", out.strip()))

# Recent candle freshness NIFTY + sample equity
sql2 = """SELECT symbol, timeframe, max(open_time) AS last_bar
FROM marketdata_candles
WHERE symbol IN ('NIFTY 50','RELIANCE','HDFCBANK') AND timeframe='1m'
GROUP BY symbol, timeframe"""
_, out = run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql2}"')
checks.append(("candles", out))

# If table name differs
_, out = run("docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -c \"SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%candle%' ORDER BY 1\"")
checks.append(("candle_tables", out.strip()))

# Recent API errors
_, out = run("docker logs stokr-api --since 30m 2>&1 | grep -E 'ERROR|ClassCastException|API_WARMING' | tail -15")
checks.append(("api_errors", out or "(none)"))

# WS ticks
_, out = run("docker logs stokr-api --since 5m 2>&1 | grep 'platform.ws.first_tick' | tail -3")
checks.append(("ticks", out or "(none)"))

c.close()

for k, v in checks:
    print(f"\n=== {k} ===")
    if isinstance(v, dict):
        print(json.dumps(v, indent=2))
    else:
        print(v)
