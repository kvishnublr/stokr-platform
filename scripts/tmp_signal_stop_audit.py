#!/usr/bin/env python3
"""Audit signal pipeline stop ~11:35 IST on 2026-06-03."""
import json
import paramiko
from datetime import datetime, timezone

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=180):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


def psql(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(
        f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform -x -c '' 2>/dev/null; "
        f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform"
    )


def psql_table(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")


if __name__ == "__main__":
    print("=== SERVER TIME ===")
    print(run("date; timedatectl 2>/dev/null | head -5 || true"))

    print("\n=== GIT / DEPLOY ===")
    print(run(f"cd {BASE} && git rev-parse --short HEAD && git log -1 --oneline"))

    queries = [
        ("last_signals_today", """
SELECT strategy_name, symbol, side, created_at AT TIME ZONE 'Asia/Kolkata' AS ist,
       execution_mode, signal_type
FROM strategy_signals
WHERE created_at >= '2026-06-03 00:00:00+00'
ORDER BY created_at DESC
LIMIT 25;
"""),
        ("signals_after_1130_ist", """
SELECT COUNT(*) AS cnt_after_1130
FROM strategy_signals
WHERE created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:30:00'
  AND created_at AT TIME ZONE 'Asia/Kolkata' < '2026-06-03 23:59:59';
"""),
        ("last_signal_per_strategy", """
SELECT strategy_name, MAX(created_at AT TIME ZONE 'Asia/Kolkata') AS last_ist, COUNT(*) AS today_cnt
FROM strategy_signals
WHERE created_at >= '2026-06-03 00:00:00+00'
GROUP BY strategy_name
ORDER BY last_ist DESC;
"""),
        ("signals_hourly", """
SELECT date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS hour_ist, COUNT(*) cnt
FROM strategy_signals
WHERE created_at >= '2026-06-03 00:00:00+00'
GROUP BY 1 ORDER BY 1;
"""),
        ("integrity_failures_today", """
SELECT reason, COUNT(*) cnt
FROM strategy_integrity_failures
WHERE created_at >= '2026-06-03 00:00:00+00'
GROUP BY reason ORDER BY cnt DESC LIMIT 15;
"""),
        ("runtime_health", """
SELECT strategy_key, execution_mode, blocked_reason, signals_today, scans_today, blocked_today, updated_at
FROM strategy_runtime_health
ORDER BY updated_at DESC NULLS LAST
LIMIT 15;
"""),
        ("flyway", """
SELECT version, description FROM flyway_schema_history
WHERE version IN ('81','82','83') ORDER BY version;
"""),
        ("currency_status", """
SELECT strategy_key, execution_mode, live_enabled FROM strategy_execution_configs
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
   OR strategy_key LIKE '%INR%';
"""),
    ]

    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql_table(sql))

    print("\n=== API HEALTH ===")
    print(run("curl -sf http://127.0.0.1:8080/actuator/health 2>&1 || echo DOWN"))

    print("\n=== CATALOG SCAN LOGS (11:00+) ===")
    print(run(
        "docker logs stokr-api --since 12h 2>&1 | "
        "grep -iE 'catalog\\.scan|signal\\.persist|feed_unhealthy|FEED_STALE|blocked|integrity' | "
        "grep -E '11:[3-9]|12:|13:|14:|15:' | tail -40 || echo none"
    ))

    print("\n=== RECENT CATALOG SCAN LOGS ===")
    print(run(
        "docker logs stokr-api --since 2h 2>&1 | "
        "grep -iE 'catalog\\.scan|feed_unhealthy|blocked_safe|signals=' | tail -30 || echo none"
    ))

    print("\n=== FEED / INGESTION LOGS ===")
    print(run(
        "docker logs stokr-api --since 12h 2>&1 | "
        "grep -iE 'feed\\.|websocket|ingestion|tick|disconnect|reconnect|paused|kill' | tail -30 || echo none"
    ))

    print("\n=== ENV FLAGS ===")
    print(run(
        "docker exec stokr-api printenv 2>/dev/null | grep -iE "
        "'CATALOG|SCAN|KILL|FEED|INGEST|PAUSE|LIVE_TRADING' | sort || echo none"
    ))
