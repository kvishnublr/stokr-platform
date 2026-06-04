#!/usr/bin/env python3
"""One-shot prod CDS / deploy verification (no secrets printed)."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=180):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode("utf-8", "replace")


def section(title, cmd):
    print(f"\n=== {title} ===\n{run(cmd).strip()}\n")


if __name__ == "__main__":
    section(
        "GIT + HEALTH",
        "cd /opt/stokr/stokr-platform && git log -1 --oneline && curl -sf http://127.0.0.1:8080/actuator/health",
    )
    section(
        "FLYWAY V85",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT version, description, success FROM flyway_schema_history WHERE version='85';\"",
    )
    section(
        "CURRENCY BINDINGS",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT sd.strategy_key, sug.group_key FROM strategy_runtime_bindings b "
        "JOIN strategy_definitions sd ON b.strategy_catalog_id = sd.id "
        "JOIN strategy_universe_groups sug ON b.universe_group_id = sug.id "
        "WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION') ORDER BY 1,2;\"",
    )
    section(
        "CDS CANDLES",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT symbol, timeframe, count(1) cnt, max(open_time) latest "
        "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
        "GROUP BY symbol, timeframe ORDER BY symbol;\"",
    )
    section(
        "CDS BACKFILL LOGS",
        "docker logs stokr-api 2>&1 | grep -i cds_backfill | tail -30",
    )
    section(
        "SIGNAL PIPELINE STARTUP",
        "docker logs stokr-api 2>&1 | grep signal.pipeline | tail -15",
    )
    section(
        "TOKEN REFRESH LOGS",
        "docker logs stokr-api 2>&1 | grep -E 'platform.zerodha.token|cds_backfill.token' | tail -15",
    )
    section(
        "CONTAINERS",
        "docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'stokr-api|stokr-ui'",
    )
    section(
        "API KEY PREFIX (masked)",
        "docker exec stokr-api sh -c 'echo ZERODHA_API_KEY=${ZERODHA_API_KEY:0:6}...'",
    )
    section(
        "STARTUP PRUNE + BACKFILL",
        "docker logs stokr-api 2>&1 | grep -E 'currency_binding_pruned|signal.pipeline.startup|cds_backfill|platform.zerodha.token' | tail -30",
    )
