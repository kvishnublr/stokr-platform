#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "cd /opt/stokr/stokr-platform && git log -1 --oneline",
    "docker logs stokr-api 2>&1 | grep cds_backfill | tail -20",
    "docker logs stokr-api 2>&1 | grep platform.zerodha | tail -15",
    "docker logs stokr-api 2>&1 | grep historical_backfill | tail -10",
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
    "\"SELECT symbol, timeframe, count(1) cnt, max(open_time) latest "
    "FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR') "
    "GROUP BY symbol, timeframe ORDER BY symbol;\"",
]
for cmd in cmds:
    print("\n===", cmd[:60], "===\n")
    _, o, e = c.exec_command(cmd, timeout=90)
    print((o.read() + e.read()).decode())
c.close()
