#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=300):
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
    sections = [
        ("docker", 'docker ps -a --filter name=stokr-api --format "{{.Names}} {{.Status}}"'),
        ("api_tail", "docker logs stokr-api --tail 100 2>&1"),
        ("api_errors", "docker logs stokr-api 2>&1 | grep -i flyway | tail -20"),
        ("api_crash", "docker logs stokr-api 2>&1 | grep -iE 'ERROR|Exception|Failed to start' | tail -25"),
        ("signals", """
SELECT strategy_name, symbol, signal_type,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM strategy_signals
WHERE created_at >= '2026-06-03'
ORDER BY created_at DESC LIMIT 12;
"""),
        ("candles", """
SELECT symbol, timeframe,
       MAX(open_time) AT TIME ZONE 'Asia/Kolkata' AS last_ist,
       COUNT(*) AS bars_today
FROM marketdata_candles
WHERE open_time >= '2026-06-02 18:30:00+00'
  AND symbol IN ('TCS', 'NIFTY 50', 'RELIANCE')
GROUP BY symbol, timeframe
ORDER BY symbol, timeframe;
"""),
        ("pipeline_audit", """
SELECT table_name FROM information_schema.tables
WHERE table_schema='public' AND table_name LIKE '%pipeline%' OR table_name LIKE '%audit%'
ORDER BY 1 LIMIT 20;
"""),
        ("signal_audit", """
SELECT table_name FROM information_schema.tables
WHERE table_schema='public' AND (table_name LIKE '%signal%' OR table_name LIKE '%feed%')
ORDER BY 1 LIMIT 30;
"""),
    ]

    for name, q in sections:
        print(f"\n=== {name} ===")
        if name.startswith("api") or name == "docker":
            print(run(q))
        else:
            print(psql(q))

    # Save full morning logs to file on server and grep
    print("\n=== morning_scan_logs ===")
    print(run(
        "docker logs stokr-api --since 14h 2>&1 | "
        "grep -E 'catalog\\.scan|feed_unhealthy|signal\\.persist|ingestion_paused|disconnect|FEED_STALE' | "
        "grep -E '05:|06:|07:|08:|09:|10:|11:' | tail -50 || echo none"
    ))
