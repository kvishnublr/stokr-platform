#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").strip()


def psql(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")


if __name__ == "__main__":
    queries = [
        ("audit_schema", "\\d signal_pipeline_audit"),
        ("audit_hourly", """
SELECT date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS hour_ist,
       event_type, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE created_at >= '2026-06-03 00:00:00+00'
GROUP BY 1, 2 ORDER BY 1, 2;
"""),
        ("audit_after_1125", """
SELECT created_at AT TIME ZONE 'Asia/Kolkata' AS ist,
       event_type, strategy_key, symbol, detail
FROM signal_pipeline_audit
WHERE created_at AT TIME ZONE 'Asia/Kolkata' BETWEEN '2026-06-03 11:20:00' AND '2026-06-03 12:00:00'
ORDER BY created_at LIMIT 30;
"""),
        ("audit_blocked_after_1130", """
SELECT event_type, strategy_key, LEFT(detail, 80) AS detail, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:30:00'
  AND created_at AT TIME ZONE 'Asia/Kolkata' < '2026-06-03 15:30:00'
GROUP BY 1,2,3 ORDER BY cnt DESC LIMIT 20;
"""),
        ("audit_scan_after_1130", """
SELECT COUNT(*) AS scan_events
FROM signal_pipeline_audit
WHERE created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:30:00'
  AND event_type ILIKE '%scan%';
"""),
        ("feed_session", """
SELECT id, session_state, websocket_state, last_tick_at AT TIME ZONE 'Asia/Kolkata' AS last_tick_ist,
       updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist, disconnect_reason
FROM platform_broker_feed_sessions
ORDER BY updated_at DESC LIMIT 5;
"""),
        ("currency_now", """
SELECT strategy_key, execution_mode, live_enabled FROM strategy_execution_configs
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
        ("currency_bindings", """
SELECT sd.strategy_key, b.runtime_enabled FROM strategy_runtime_bindings b
JOIN strategy_definitions sd ON sd.id=b.strategy_catalog_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
    ]
    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql(sql))

    print("\n=== nginx upstream ===")
    print(run("grep -r proxy_pass /etc/nginx/ 2>/dev/null | grep 8080 | head -5 || docker ps --format '{{.Names}} {{.Ports}}' | grep 8080"))

    print("\n=== api-new logs scan ===")
    print(run(
        "docker logs stokr-api-new --since 14h 2>&1 | "
        "grep -iE 'catalog\\.scan|signal\\.persist|feed_unhealthy|ingestion_paused|Started StokrApplication' | "
        "tail -40 || echo none"
    ))

    print("\n=== api-new health ===")
    print(run("curl -sf http://127.0.0.1:8080/actuator/health 2>&1; docker inspect stokr-api-new --format '{{.State.Health.Status}}' 2>/dev/null"))
