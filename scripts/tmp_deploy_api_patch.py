#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=900):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    c.close()
    with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\scripts\tmp_deploy_out.txt", "a", encoding="utf-8") as f:
        f.write(f"\n$ {cmd}\n{out}\nEXIT={code}\n")
    return code


run(f"cd {BASE} && git pull origin Release_v1")
run(f"cd {BASE} && bash deploy.sh api")
time.sleep(120)
sql = (
    "SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) latest "
    "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
    "GROUP BY symbol, timeframe ORDER BY symbol;"
)
run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
run("docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT symbol, instrument_token, trading_symbol FROM strategy_universe_symbols WHERE exchange='CDS';\"")
run("docker logs stokr-api 2>&1 | grep -E 'cds_backfill|universe.instrument.enriched exchange=CDS' | tail -20")
print("api patch deploy done")
