#!/usr/bin/env python3
import paramiko
import sys
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=600):
    print(f"\n$ {cmd}")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    c.close()
    print(out)
    return code, out


def main():
    run("sleep 15")
    code, _ = run(f"cd {BASE} && docker compose --profile app up -d api")
    if code != 0:
        run(f"cd {BASE} && docker rm -f stokr-api 2>/dev/null; docker compose --profile app up -d api")
    run("sleep 45")
    run(f"cd {BASE} && docker compose --profile app build ui")
    run(f"cd {BASE} && docker compose --profile app up -d --no-deps ui")
    run("sleep 15")
    run(f"cd {BASE} && docker compose ps api ui")
    run("curl -sf http://127.0.0.1:8080/actuator/health | head -c 400 || echo API_HEALTH_FAIL")
    print("\nWaiting 100s for CDS backfill startup...")
    time.sleep(100)
    sql = (
        "SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) latest "
        "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
        "GROUP BY symbol, timeframe ORDER BY symbol;"
    )
    run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')
    run("docker logs stokr-api 2>&1 | grep -i cds_backfill | tail -20")


if __name__ == "__main__":
    main()
