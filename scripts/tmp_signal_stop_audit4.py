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
    queries = [
        ("audit_hourly", """
SELECT date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS hour_ist,
       pipeline_stage, execution_status, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE created_at >= '2026-06-03 00:00:00+00'
GROUP BY 1,2,3 ORDER BY 1,2,3;
"""),
        ("persisted_signals_audit", """
SELECT date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS hour_ist, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE created_at >= '2026-06-03 00:00:00+00'
  AND pipeline_stage = 'PERSISTED'
GROUP BY 1 ORDER BY 1;
"""),
        ("adv_cash_after_1100", """
SELECT created_at AT TIME ZONE 'Asia/Kolkata' AS ist,
       pipeline_stage, execution_status, rejection_code, symbol
FROM signal_pipeline_audit
WHERE strategy_key = 'ADV_CASH'
  AND created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:00:00'
ORDER BY created_at LIMIT 40;
"""),
        ("rejections_after_1130", """
SELECT rejection_code, strategy_key, COUNT(*) cnt
FROM signal_pipeline_audit
WHERE created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:30:00'
  AND created_at AT TIME ZONE 'Asia/Kolkata' < '2026-06-03 15:30:00'
  AND rejection_code IS NOT NULL
GROUP BY 1,2 ORDER BY cnt DESC LIMIT 25;
"""),
        ("audit_rows_after_1130", """
SELECT COUNT(*) AS total_rows,
       COUNT(*) FILTER (WHERE pipeline_stage='PERSISTED') AS persisted,
       COUNT(*) FILTER (WHERE pipeline_stage='GENERATED') AS generated
FROM signal_pipeline_audit
WHERE created_at AT TIME ZONE 'Asia/Kolkata' >= '2026-06-03 11:30:00'
  AND created_at AT TIME ZONE 'Asia/Kolkata' < '2026-06-03 15:30:00';
"""),
        ("feed_session_cols", """
SELECT column_name FROM information_schema.columns
WHERE table_name='platform_broker_feed_sessions' ORDER BY ordinal_position;
"""),
        ("feed_session", """
SELECT * FROM platform_broker_feed_sessions ORDER BY updated_at DESC LIMIT 2;
"""),
        ("operational_audit", """
SELECT event_type, LEFT(message,100) AS msg,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM operational_audit_events
WHERE created_at >= '2026-06-03 03:00:00+00'
  AND (event_type ILIKE '%feed%' OR event_type ILIKE '%disconnect%' OR message ILIKE '%ingestion%')
ORDER BY created_at DESC LIMIT 20;
"""),
    ]
    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql(sql))

    print("\n=== api-new morning persist logs ===")
    print(run(
        "docker logs stokr-api-new 2>&1 | "
        "grep -E 'signal\\.persist|catalog\\.scan\\.cycle_done.*signals=[1-9]' | "
        "grep '2026-06-03' | tail -30 || "
        "docker logs stokr-api-new 2>&1 | grep 'signal.persist' | tail -20"
    ))

    print("\n=== api-new restart events ===")
    print(run(
        "docker logs stokr-api-new 2>&1 | grep -E 'Starting StokrApplication|catalog.scan.start' | tail -20"
    ))
