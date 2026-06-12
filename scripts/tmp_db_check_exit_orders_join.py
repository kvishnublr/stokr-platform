#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
DB = "stokr_platform"


def ssh_run(c: paramiko.SSHClient, cmd: str) -> str:
    _, stdout, stderr = c.exec_command(cmd)
    return (stdout.read() + stderr.read()).decode("utf-8", "replace")


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    print("=== strategy_signals exit outcomes in last 6h (show ids + exit order count) ===", flush=True)

    print("\n=== oms_orders columns containing 'del' (quick schema check) ===", flush=True)
    schema_sql = (
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name='oms_orders' AND column_name ILIKE '%del%' ORDER BY column_name;"
    )
    schema_cmd = (
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c " + '"' + schema_sql + '"'
    )
    print(ssh_run(c, schema_cmd).strip())

    sql = r"""
WITH exit_signals AS (
  SELECT id, user_id, strategy_name, symbol, outcome_status, outcome_time
  FROM strategy_signals
  WHERE outcome_time IS NOT NULL
    AND outcome_status IN ('PRESSURE_EXIT','FEED_PROTECTION','LIQUIDITY_PROTECTION','TIME_EXIT','TARGET_HIT','STOPLOSS_HIT','SL_HIT','BREAKEVEN_EXIT','MANUAL')
    AND outcome_time >= (NOW() - INTERVAL '6 hours')
)
SELECT
  es.id,
  es.user_id,
  es.strategy_name,
  es.symbol,
  es.outcome_status,
  es.outcome_time,
  COUNT(o.id) AS oms_orders_total_non_deleted,
  SUM(CASE WHEN o.idempotency_key LIKE 'outcome-exit:%' THEN 1 ELSE 0 END) AS oms_exit_orders_non_deleted
FROM exit_signals es
LEFT JOIN oms_orders o
  ON o.signal_id = es.id AND o.deleted = false
GROUP BY
  es.id, es.user_id, es.strategy_name, es.symbol, es.outcome_status, es.outcome_time
ORDER BY es.outcome_time DESC
LIMIT 20;
"""

    psql_cmd = (
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        + '"' + sql.replace('"', '\\"').replace("\n", " ").strip() + '"'
    )

    out = ssh_run(c, psql_cmd)
    print(out.strip())

    print("\n=== sample: does oms_orders have any idempotency_key containing outcome-exit at all? (count last 24h) ===", flush=True)
    sql2 = r"SELECT COUNT(*) FROM oms_orders WHERE created_at >= (NOW() - INTERVAL '24 hours') AND idempotency_key ILIKE '%outcome-exit%';"
    psql_cmd2 = (
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        + '"' + sql2.replace('"', '\\"') + '"'
    )
    out2 = ssh_run(c, psql_cmd2)
    print(out2.strip())

    c.close()
    print("done", flush=True)


if __name__ == "__main__":
    main()

