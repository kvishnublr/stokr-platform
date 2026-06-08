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
SELECT
  id,
  user_id,
  signal_source,
  owner_type,
  pipeline,
  is_test_trade,
  expired,
  outcome_status,
  outcome_time,
  symbol,
  strategy_name,
  lifecycle_status
FROM strategy_signals
WHERE outcome_time IS NOT NULL
  AND outcome_status IN ('PRESSURE_EXIT','FEED_PROTECTION','LIQUIDITY_PROTECTION','TIME_EXIT','TARGET_HIT','STOPLOSS_HIT','SL_HIT','BREAKEVEN_EXIT','MANUAL')
  AND outcome_time >= (NOW() - INTERVAL '6 hours')
ORDER BY outcome_time DESC
LIMIT 15;
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

