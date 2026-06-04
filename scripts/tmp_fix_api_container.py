#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    c.close()
    print(out)
    return code


run(f"cd {BASE} && git pull origin Release_v1")
run("docker ps -a --format '{{.Names}} {{.Status}}' | grep stokr-api")
run("docker rm -f stokr-api 2>/dev/null; docker ps -aq --filter name=stokr-api | xargs -r docker rm -f")
run(f"cd {BASE} && docker compose --profile app up -d api")
run("sleep 120")
run("curl -sf http://127.0.0.1:8080/actuator/health")
run("docker logs stokr-api 2>&1 | grep -E 'cds_backfill|enriched exchange=CDS|auto_subscribe exchange=CDS' | tail -25")
sql = (
    "SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) latest "
    "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
    "GROUP BY symbol, timeframe ORDER BY symbol;"
)
run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
run("docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT symbol, instrument_token, trading_symbol FROM strategy_universe_symbols WHERE exchange='CDS';\"")
