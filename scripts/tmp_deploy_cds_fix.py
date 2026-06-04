#!/usr/bin/env python3
"""Deploy d90a84d+ to prod and verify CDS."""
import paramiko
import sys
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=900):
    print(f"\n$ {cmd}")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    c.close()
    print(out[-8000:] if len(out) > 8000 else out)
    if code != 0:
        print(f"EXIT {code}", file=sys.stderr)
    return code, out


def main():
    steps = [
        f"cd {BASE} && git fetch origin Release_v1 && git reset --hard origin/Release_v1 && git log -1 --oneline",
        "docker ps -a --format '{{.Names}}' | grep stokr-api | xargs -r docker rm -f",
        # Remove stale API container if name collision blocks compose
        "docker rm -f stokr-api 2>/dev/null || true",
        f"cd {BASE} && chmod +x deploy.sh && ./deploy.sh api",
        "sleep 120",
        "curl -sf http://127.0.0.1:8080/actuator/health || echo API_HEALTH_FAIL",
        "docker logs stokr-api 2>&1 | grep -E 'cds_backfill|currency_binding_pruned|Flyway.*V86' | tail -25",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT version FROM flyway_schema_history WHERE version='86';\"",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT sd.strategy_key, sug.group_key FROM strategy_runtime_bindings b "
        "JOIN strategy_definitions sd ON b.strategy_catalog_id = sd.id "
        "JOIN strategy_universe_groups sug ON b.universe_group_id = sug.id "
        "WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION') ORDER BY 1,2;\"",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        "\"SELECT symbol, timeframe, count(1) cnt, max(open_time) latest "
        "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
        "GROUP BY symbol, timeframe ORDER BY symbol;\"",
    ]
    for cmd in steps:
        code, out = run(cmd)
        if code != 0 and "docker rm" not in cmd and "curl" not in cmd:
            if "Conflict" in out or "already in use" in out:
                run("docker rm -f stokr-api 2>/dev/null || true")
                code2, _ = run(f"cd {BASE} && ./deploy.sh api")
                if code2 != 0:
                    sys.exit(code2)
            else:
                sys.exit(code)


if __name__ == "__main__":
    main()
