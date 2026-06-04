#!/usr/bin/env python3
"""Deploy currency strategies commit and verify."""
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
COMMIT = "02a37b3"


def run(cmd, timeout=3600):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


def psql(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")


if __name__ == "__main__":
    steps = [
        f"cd {BASE} && git fetch origin Release_v1 && git checkout Release_v1 && git pull origin Release_v1",
        f"cd {BASE} && git rev-parse --short HEAD && git log -1 --oneline",
        f"cd {BASE} && docker compose --profile app build api 2>&1 | tail -25",
        f"cd {BASE} && docker compose --profile app up -d --no-deps api 2>&1",
    ]
    for s in steps:
        print(f"\n>>> {s[:90]}")
        print(run(s)[-4000:])

    print("\n>>> waiting for api...")
    time.sleep(50)
    print(run("curl -sf http://127.0.0.1:8080/actuator/health"))
    print(run("docker exec stokr-api printenv STOKR_GIT_COMMIT 2>/dev/null || echo none"))

    checks = [
        ("flyway", "SELECT version, description FROM flyway_schema_history WHERE version IN ('81','82','83') ORDER BY version"),
        ("currency_defs", """
SELECT strategy_key, segment, validation_status
FROM strategy_definitions
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
        ("currency_exec", """
SELECT strategy_key, execution_mode, live_enabled, paper_enabled
FROM strategy_execution_configs
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
        ("bindings", """
SELECT sd.strategy_key, b.runtime_enabled, ug.group_key
FROM strategy_runtime_bindings b
JOIN strategy_definitions sd ON sd.id = b.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = b.universe_group_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
        ("cds_candles", """
SELECT symbol, timeframe, COUNT(*) cnt, MAX(bar_time) last_bar
FROM marketdata_candles
WHERE symbol IN ('USDINR','EURINR')
GROUP BY symbol, timeframe
ORDER BY symbol, timeframe;
"""),
        ("signals", """
SELECT strategy_name, COUNT(*) cnt, MAX(created_at) last_sig
FROM strategy_signals
WHERE strategy_name IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
GROUP BY strategy_name;
"""),
        ("all_exec_modes", """
SELECT strategy_key, execution_mode, live_enabled
FROM strategy_execution_configs
WHERE user_id IS NULL AND deleted = false
ORDER BY strategy_key;
"""),
    ]
    for name, sql in checks:
        print(f"\n=== {name} ===")
        print(psql(sql))

    print("\n>>> api logs currency")
    print(run("docker logs stokr-api --since 3m 2>&1 | grep -iE 'USDINR|EURINR|currency|V83|Flyway' | tail -20 || echo none"))
