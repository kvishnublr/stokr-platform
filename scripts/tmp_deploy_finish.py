#!/usr/bin/env python3
import paramiko
import sys
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
OUT = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\scripts\tmp_deploy_out.txt"


def run(cmd, timeout=900):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    c.close()
    with open(OUT, "a", encoding="utf-8") as f:
        f.write(f"\n$ {cmd}\n{out}\nEXIT={code}\n")
    return code


def main():
    open(OUT, "w", encoding="utf-8").write("deploy retry 2\n")
    run(f"cd {BASE} && docker compose --profile app up -d --no-deps ui")
    run("sleep 15")
    run(f"cd {BASE} && docker compose ps api ui")
    run("curl -sf http://127.0.0.1:8080/actuator/health || echo API_HEALTH_FAIL")
    time.sleep(90)
    sql = (
        "SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) latest "
        "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
        "GROUP BY symbol, timeframe ORDER BY symbol;"
    )
    run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
    run("docker logs stokr-api 2>&1 | grep cds_backfill | tail -15")
    print("Wrote", OUT)


if __name__ == "__main__":
    main()
