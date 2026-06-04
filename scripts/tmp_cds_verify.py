#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode("utf-8", "replace")


if __name__ == "__main__":
    checks = [
        "docker logs stokr-api 2>&1 | grep -E 'auto_subscribe exchange=CDS|cds_backfill|platform.ws.auto_subscribe' | tail -20",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT symbol, instrument_token, trading_symbol FROM strategy_universe_symbols WHERE exchange='CDS';\"",
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT vendor_code, token_expires_at, ingestion_paused FROM platform_broker_feed_sessions WHERE deleted=false;\"",
    ]
    for cmd in checks:
        print("\n===", cmd[:60], "===\n")
        print(run(cmd))
