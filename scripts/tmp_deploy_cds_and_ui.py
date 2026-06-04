#!/usr/bin/env python3
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
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    c.close()
    print(out)
    if code != 0:
        print(f"EXIT {code}", file=sys.stderr)
    return code, out


def main():
    steps = [
        f"cd {BASE} && git stash push -m auto-deploy -u 2>/dev/null || true",
        f"cd {BASE} && git pull origin Release_v1",
        f"cd {BASE} && bash deploy.sh api",
        f"cd {BASE} && docker compose --profile app up -d --no-deps ui",
        "sleep 20",
        f"cd {BASE} && docker compose ps api ui",
        "curl -sf http://127.0.0.1:8080/actuator/health | head -c 500 || echo API_HEALTH_FAIL",
    ]
    for cmd in steps:
        code, _ = run(cmd)
        if code != 0 and "git stash" not in cmd and "curl" not in cmd:
            sys.exit(code)

    # verify CDS candles after API restart (backfill runs ~90s after startup)
    print("\nWaiting 100s for CDS backfill startup job...")
    time.sleep(100)
    run(
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -c '
        '"SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) latest '
        'FROM marketdata_candles WHERE symbol IN (\'USDINR\',\'EURINR\') '
        'GROUP BY symbol, timeframe ORDER BY symbol;"'
    )


if __name__ == "__main__":
    main()
