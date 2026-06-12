#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    sql = r"""
WITH exit_signals AS (
  SELECT id, symbol, outcome_status, outcome_time
  FROM strategy_signals
  WHERE outcome_time IS NOT NULL
    AND outcome_status IN ('PRESSURE_EXIT','FEED_PROTECTION','LIQUIDITY_PROTECTION','TIME_EXIT','TARGET_HIT','STOPLOSS_HIT','SL_HIT','BREAKEVEN_EXIT','MANUAL')
    AND outcome_time >= (NOW() - INTERVAL '6 hours')
)
SELECT
  es.id,
  es.symbol,
  es.outcome_status,
  es.outcome_time,
  o.state,
  COUNT(*) AS oms_orders_count
FROM exit_signals es
JOIN oms_orders o
  ON o.signal_id = es.id
  AND o.deleted = false
GROUP BY
  es.id, es.symbol, es.outcome_status, es.outcome_time, o.state
ORDER BY es.outcome_time DESC
LIMIT 60;
"""

    cmd = (
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
        + '"' + sql.replace('"', '\\"').replace("\n", " ").strip() + '"'
    )
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    print(out.strip())
    c.close()


if __name__ == "__main__":
    main()

