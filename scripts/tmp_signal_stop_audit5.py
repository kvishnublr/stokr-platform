#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").strip()


def psql(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")


if __name__ == "__main__":
    print("=== scan logs 05:50-06:15 UTC (11:20-11:45 IST) ===")
    print(run(
        "docker logs stokr-api-new 2>&1 | "
        "grep 'catalog.scan.binding_done' | "
        "grep -E '05:5|06:0|06:1' | head -30"
    ))

    print("\n=== runtime_health schema ===")
    print(psql("SELECT column_name FROM information_schema.columns WHERE table_name='strategy_runtime_health' ORDER BY 1"))

    print("\n=== runtime_health today ===")
    print(psql("""
SELECT * FROM strategy_runtime_health ORDER BY updated_at DESC NULLS LAST LIMIT 12;
"""))

    print("\n=== duplicate currency bindings ===")
    print(psql("""
SELECT sd.strategy_key, b.id, b.runtime_enabled, ug.group_key
FROM strategy_runtime_bindings b
JOIN strategy_definitions sd ON sd.id=b.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id=b.universe_group_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""))

    print("\n=== CDS candles ===")
    print(psql("""
SELECT symbol, timeframe, COUNT(*) cnt, MAX(open_time) AT TIME ZONE 'Asia/Kolkata' AS last_ist
FROM marketdata_candles WHERE symbol IN ('USDINR','EURINR')
GROUP BY symbol, timeframe;
"""))

    print("\n=== feed NOW ===")
    print(psql("""
SELECT connection_state, websocket_state, ingestion_paused,
       last_tick_at AT TIME ZONE 'Asia/Kolkata' AS last_tick_ist,
       last_packet_at AT TIME ZONE 'Asia/Kolkata' AS last_packet_ist,
       subscription_count, disconnect_reason
FROM platform_broker_feed_sessions ORDER BY updated_at DESC LIMIT 1;
"""))
