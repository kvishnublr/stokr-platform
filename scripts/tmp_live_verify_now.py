#!/usr/bin/env python3
"""Live prod verification: app health + strategies."""
import json
import paramiko
import urllib.request

HOST = "173.249.55.84"
PWD = "Temp1234.."
MAIN = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=120):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace").strip()
    code = o.channel.recv_exit_status()
    return code, out


def psql(sql):
    sql_one = " ".join(sql.split())
    cmd = f'docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -F"|" -c "{sql_one}"'
    code, out = run(cmd)
    return code, out


results = {}

# Time
_, out = run("TZ=Asia/Kolkata date '+%Y-%m-%d %H:%M:%S %Z'")
results["ist_now"] = out

# Containers
_, out = run("docker ps --format '{{.Names}}|{{.Status}}' | grep stokr | sort")
results["containers"] = [ln for ln in out.splitlines() if ln]

# API health
_, out = run("curl -sf http://127.0.0.1:8080/actuator/health")
try:
    health = json.loads(out)
    results["api_status"] = health.get("status")
    results["api_components"] = {
        k: v.get("status") if isinstance(v, dict) else v
        for k, v in health.get("components", {}).items()
    }
except Exception:
    results["api_status"] = "UNKNOWN"
    results["api_raw"] = out[:500]

# Key env flags
_, out = run(
    f"grep -E '^STOKR_EXECUTION_LIVE_TRADING_ENABLED=|^STOKR_CONFIDENCE_AUTO_TRADE_ENABLED=' {MAIN}/.env"
)
results["env_flags"] = out

# Zerodha platform feed
code, out = psql(
    """
SELECT vendor_code, connection_state, websocket_state,
       access_token_enc IS NOT NULL, token_expires_at, updated_at
FROM platform_broker_feed_sessions
WHERE deleted = false ORDER BY updated_at DESC LIMIT 1;
"""
)
results["zerodha_feed"] = out if code == 0 else f"ERR: {out}"

# Table discovery for signals
code, out = psql(
    """
SELECT table_name FROM information_schema.tables
WHERE table_schema='public' AND table_name LIKE '%signal%'
ORDER BY table_name;
"""
)
results["signal_tables"] = out.splitlines() if code == 0 else []

# Try common signal table columns
for tbl in ["trading_signals", "strategy_signals", "signal_events"]:
    code, out = psql(
        f"SELECT column_name FROM information_schema.columns WHERE table_name='{tbl}' ORDER BY ordinal_position LIMIT 20;"
    )
    if code == 0 and out:
        results[f"columns_{tbl}"] = out.splitlines()

# Signals today - discover and query
code, cols = psql(
    "SELECT column_name FROM information_schema.columns WHERE table_name='trading_signals';"
)
if code == 0 and cols:
    colset = set(cols.splitlines())
    strat_col = next(
        (c for c in ["strategy_key", "strategy_code", "strategy_id", "strategy_name"] if c in colset),
        None,
    )
    time_col = next((c for c in ["created_at", "signal_time", "generated_at"] if c in colset), None)
    if strat_col and time_col:
        code, out = psql(
            f"""
SELECT {strat_col}, count(*), max({time_col})
FROM trading_signals
WHERE {time_col} >= current_date
GROUP BY {strat_col} ORDER BY count(*) DESC;
"""
        )
        results["signals_today"] = out if code == 0 and out else "(none)"

# Orders today
code, out = psql(
    """
SELECT column_name FROM information_schema.columns
WHERE table_name='oms_orders' AND column_name LIKE '%strat%';
"""
)
strat_cols = out.splitlines() if code == 0 else []
sk = strat_cols[0] if strat_cols else None
if sk:
    code, out = psql(
        f"""
SELECT {sk}, count(*), max(created_at)
FROM oms_orders WHERE created_at >= current_date
GROUP BY {sk} ORDER BY count(*) DESC;
"""
    )
    results["orders_today"] = out if code == 0 and out else "(none)"
else:
    code, out = psql(
        "SELECT count(*), max(created_at) FROM oms_orders WHERE created_at >= current_date;"
    )
    results["orders_today"] = out

# Runtime health table
code, out = psql(
    "SELECT column_name FROM information_schema.columns WHERE table_name='strategy_runtime_health';"
)
if code == 0 and out:
    results["runtime_health_cols"] = out.splitlines()
    code2, out2 = psql(
        """
SELECT strategy_code, scan_cycles, integrity_blocked_count, signals_emitted, updated_at
FROM strategy_runtime_health
WHERE trading_date = current_date
ORDER BY scan_cycles DESC NULLS LAST LIMIT 12;
"""
    )
    if code2 != 0:
        code2, out2 = psql(
            """
SELECT * FROM strategy_runtime_health
WHERE trading_date = current_date LIMIT 5;
"""
        )
    results["runtime_health_today"] = out2 if code2 == 0 and out2 else "(none or schema mismatch)"

# Active bindings
code, out = psql(
    """
SELECT count(*) FROM strategy_runtime_bindings
WHERE enabled = true AND deleted = false;
"""
)
results["active_bindings"] = out

# Execution configs
code, out = psql(
    """
SELECT strategy_code, execution_mode, enabled
FROM strategy_execution_configs
WHERE enabled = true
ORDER BY strategy_code;
"""
)
if code != 0:
    code, out = psql(
        """
SELECT strategy_code, execution_mode FROM strategy_execution_configs ORDER BY strategy_code;
"""
    )
results["execution_configs"] = out.splitlines() if code == 0 else [out]

# Unreconciled / open signals last 7d
code, out = psql(
    """
SELECT column_name FROM information_schema.columns
WHERE table_name='trading_signals' AND column_name IN ('status','lifecycle_status','signal_status');
"""
)
status_col = out.splitlines()[0] if code == 0 and out else None
if status_col:
    code, out = psql(
        f"""
SELECT {status_col}, count(*)
FROM trading_signals
WHERE created_at >= current_date - interval '7 days'
  AND {status_col} IN ('AWAITING_CLOSE','UNRECONCILED_TRADE','OPEN')
GROUP BY {status_col};
"""
    )
    results["signal_status_7d"] = out if code == 0 and out else "(none)"

# Ghost positions
code, out = psql(
    """
SELECT symbol, side, quantity, updated_at
FROM portfolio_positions
WHERE quantity != 0
ORDER BY updated_at DESC LIMIT 10;
"""
)
results["open_positions"] = out.splitlines() if code == 0 and out else ["(none)"]

# Recent API logs
_, out = run(
    "docker logs stokr-api --since 20m 2>&1 | grep -E "
    "'catalog.scan|integrity|UNRECONCILED|platform.ws|oauth|ERROR' | tail -25"
)
results["recent_logs"] = out.splitlines()[-25:] if out else []

c.close()

# Print report
print(json.dumps(results, indent=2, default=str))
