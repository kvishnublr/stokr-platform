#!/usr/bin/env python3
"""One-shot prod check: currency strategies LIVE vs PAPER."""
import base64
import paramiko

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace").strip()
    c.close()
    return out


def psql(sql):
    b64 = base64.b64encode(sql.encode()).decode()
    return run(
        f"echo {b64} | base64 -d | "
        "docker exec -i stokr-postgres psql -U postgres -d stokr_platform"
    )


QUERIES = [
    ("1_defs_exec", """
SELECT sd.strategy_key,
       sd.execution_mode AS def_exec_mode,
       sd.validation_status,
       sd.supports_live, sd.supports_paper, sd.enabled,
       sec.execution_mode AS cfg_exec_mode,
       sec.live_enabled, sec.paper_enabled
FROM strategy_definitions sd
LEFT JOIN strategy_execution_configs sec
  ON sec.strategy_key = sd.strategy_key AND sec.user_id IS NULL
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
    ("2_bindings_universe", """
SELECT sd.strategy_key, b.runtime_enabled, ug.group_key, b.scan_interval_seconds
FROM strategy_runtime_bindings b
JOIN strategy_definitions sd ON sd.id = b.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = b.universe_group_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
    ("3_signals_7d", """
SELECT strategy_name, COUNT(*) cnt,
       MAX(created_at) AT TIME ZONE 'Asia/Kolkata' AS last_ist
FROM strategy_signals
WHERE strategy_name IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND deleted = false
  AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY strategy_name;
"""),
    ("3_signals_today", """
SELECT strategy_name, COUNT(*) cnt
FROM strategy_signals
WHERE strategy_name IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND deleted = false
  AND created_at >= CURRENT_DATE
GROUP BY strategy_name;
"""),
    ("4_candles", """
SELECT symbol, timeframe, COUNT(*) cnt,
       MAX(open_time) AT TIME ZONE 'Asia/Kolkata' AS last_candle_ist
FROM marketdata_candles
WHERE symbol IN ('USDINR','EURINR') AND deleted = false
GROUP BY symbol, timeframe
ORDER BY symbol, timeframe;
"""),
    ("5_feed_session", """
SELECT connection_state, websocket_state, ingestion_paused,
       subscription_count,
       last_tick_at AT TIME ZONE 'Asia/Kolkata' AS last_tick_ist,
       last_packet_at AT TIME ZONE 'Asia/Kolkata' AS last_packet_ist,
       disconnect_reason
FROM platform_broker_feed_sessions
ORDER BY updated_at DESC LIMIT 2;
"""),
    ("5_cds_universe", """
SELECT g.group_key, g.enabled, s.symbol, s.exchange, s.enabled AS sym_enabled
FROM strategy_universe_groups g
JOIN strategy_universe_symbols s ON s.group_id = g.id
WHERE g.group_key = 'CDS_MAJOR_PAIRS';
"""),
    ("6_instances", """
SELECT sd.strategy_key, si.runtime_state, si.execution_mode, si.enabled
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.definition_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND si.deleted = false
ORDER BY si.updated_at DESC LIMIT 15;
"""),
    ("6_running", """
SELECT sd.strategy_key,
       COUNT(*) FILTER (WHERE si.runtime_state = 'RUNNING') AS running
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.definition_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND si.deleted = false
GROUP BY sd.strategy_key;
"""),
    ("flyway_83", """
SELECT version, description, success, installed_on
FROM flyway_schema_history WHERE version IN ('81','82','83') ORDER BY version;
"""),
    ("pipeline_audit_currency", """
SELECT strategy_key, pipeline_stage, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND created_at >= NOW() - INTERVAL '3 days'
GROUP BY strategy_key, pipeline_stage
ORDER BY strategy_key, cnt DESC;
"""),
]


if __name__ == "__main__":
    for name, sql in QUERIES:
        print(f"\n=== {name} ===")
        print(psql(sql))

    print("\n=== feed_env ===")
    for container in ["stokr-api-new", "stokr-api"]:
        print(f"--- {container} ---")
        print(
            run(
                f"docker exec {container} printenv 2>/dev/null "
                r"| grep -iE 'SUBSCRIPTION|UNIVERSE|CDS|STOKR_EXEC' | sort || echo none"
            )
        )

    print("\n=== api_containers ===")
    print(run('docker ps --format "{{.Names}} {{.Status}}" | grep stokr-api'))

    print("\n=== feed_subscription_props ===")
    print(
        run(
            "docker exec stokr-api-new sh -c "
            "'grep -h subscriptionUniverse /app/BOOT-INF/classes/application*.yml 2>/dev/null | head -3'"
        )
    )
    print(
        run(
            "grep -r CDS_MAJOR /opt/stokr/stokr-platform/stokr-bootstrap/src/main/resources 2>/dev/null | head -5 || echo no_cds_in_repo_on_disk"
        )
    )

    print("\n=== nginx_upstream ===")
    print(run("grep -r proxy_pass /etc/nginx/sites-enabled/ 2>/dev/null | head -8"))

    print("\n=== jar_subscription_config ===")
    for container in ["stokr-api", "stokr-api-new"]:
        print(f"--- {container} ---")
        print(
            run(
                f"docker exec {container} sh -c "
                "'unzip -p /app/app.jar BOOT-INF/classes/application-prod.yml 2>/dev/null "
                "| grep -i subscription; "
                "unzip -p /app/app.jar BOOT-INF/classes/application.yml 2>/dev/null "
                "| grep -i subscription' | head -8"
            )
        )

    print("\n=== scan_logs_currency ===")
    for container in ["stokr-api", "stokr-api-new"]:
        print(f"--- {container} ---")
        print(
            run(
                f"docker logs {container} --since 3h 2>&1 "
                "| grep -iE 'USDINR|EURINR|CDS_MAJOR' | tail -10"
            )
            or "none"
        )
