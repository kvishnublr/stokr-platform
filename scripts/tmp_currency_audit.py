#!/usr/bin/env python3
"""Audit currency strategies on prod."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


def psql(sql):
    escaped = sql.replace('"', '\\"')
    return run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{escaped}"')


QUERIES = [
    ("flyway", "SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10"),
    ("currency_defs", """
        SELECT strategy_key, display_name, segment, asset_class, enabled, validation_status,
               LEFT(parameter_metadata_json::text, 80) AS meta_preview
        FROM strategy_definitions
        WHERE strategy_key ILIKE '%INR%'
           OR segment = 'CDS'
           OR asset_class ILIKE '%CURR%'
        ORDER BY strategy_key
    """),
    ("bindings", """
        SELECT sd.strategy_key, b.runtime_enabled, ug.group_key, b.scan_interval_seconds
        FROM strategy_runtime_bindings b
        JOIN strategy_definitions sd ON sd.id = b.strategy_catalog_id
        JOIN strategy_universe_groups ug ON ug.id = b.universe_group_id
        WHERE sd.strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
    """),
    ("exec_configs", """
        SELECT strategy_key, execution_mode, live_enabled, paper_enabled, user_id
        FROM strategy_execution_configs
        WHERE strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
    """),
    ("signals_today", """
        SELECT strategy_key, COUNT(*) AS cnt, MAX(created_at) AS last_sig
        FROM strategy_signals
        WHERE strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
          AND created_at >= CURRENT_DATE
        GROUP BY strategy_key
    """),
    ("signals_7d", """
        SELECT strategy_key, COUNT(*) AS cnt, MAX(created_at) AS last_sig
        FROM strategy_signals
        WHERE strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
          AND created_at >= NOW() - INTERVAL '7 days'
        GROUP BY strategy_key
    """),
    ("instances", """
        SELECT si.id, sd.strategy_key, si.status, si.execution_mode, si.created_at
        FROM strategy_instances si
        JOIN strategy_definitions sd ON sd.id = si.strategy_definition_id
        WHERE sd.strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
        ORDER BY si.created_at DESC LIMIT 10
    """),
    ("universe_cds", """
        SELECT group_key, segment, enabled, symbol_count
        FROM strategy_universe_groups
        WHERE segment = 'CDS' OR group_key ILIKE '%CDS%' OR group_key ILIKE '%USD%' OR group_key ILIKE '%EUR%'
    """),
    ("env_modes", """
        docker exec stokr-api printenv 2>/dev/null | grep -E 'STOKR_EXEC|CATALOG|KILL|USDINR|EURINR' || echo 'no matching env'
    """),
]


if __name__ == "__main__":
    print("=== GIT ===")
    print(run(f"cd {BASE} && git rev-parse HEAD && git log -1 --oneline"))
    print("\n=== V82 on disk ===")
    print(run(f"test -f {BASE}/stokr-bootstrap/src/main/resources/db/migration/V82__currency_strategy_metadata.sql && echo EXISTS || echo MISSING"))

    for name, q in QUERIES:
        print(f"\n=== {name} ===")
        if name == "env_modes":
            print(run(q))
        else:
            print(psql(q))
