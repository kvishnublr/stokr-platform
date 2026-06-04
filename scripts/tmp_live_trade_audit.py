#!/usr/bin/env python3
"""Audit prod signals vs OMS orders for LIVE strategies today."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
BASE = "/opt/stokr/stokr-platform"

SQL = r"""
SELECT s.strategy_name, s.symbol, s.signal_type, s.created_at,
       o.execution_mode, o.state, o.reject_reason, o.created_at AS order_at
FROM strategy_signals s
LEFT JOIN oms_orders o ON o.signal_id = s.id AND o.deleted = false
WHERE s.deleted = false
  AND s.created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND s.strategy_name IN ('ADV_CASH','GAP_FILL','PRE_OPEN_GAP_OI')
ORDER BY s.created_at DESC
LIMIT 30;
"""

SQL2 = r"""
SELECT strategy_key, execution_mode, state, COUNT(*)
FROM oms_orders
WHERE deleted = false
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
GROUP BY 1,2,3
ORDER BY 1,2,3;
"""

SQL3 = r"""
SELECT block_code, COUNT(*) FROM oms_safety_blocked_orders
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
GROUP BY 1 ORDER BY 2 DESC LIMIT 15;
"""


def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    c.close()
    return code, out


def psql(q):
    q = q.replace("'", "'\"'\"'")
    return (
        f"cd {BASE} && docker compose exec -T postgres "
        f"psql -U postgres -d stokr_platform -c '{q}'"
    )


SQL4 = r"""
SELECT strategy_key, pipeline_stage, execution_status, rejection_code, rejection_message, created_at
FROM signal_pipeline_audit
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
  AND strategy_key IN ('ADV_CASH','GAP_FILL','PRE_OPEN_GAP_OI')
ORDER BY created_at DESC LIMIT 25;
"""

SQL5 = r"""
SELECT COUNT(*) FROM oms_orders WHERE deleted=false
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata';
"""

SQL6 = r"""
SELECT id, strategy_name, symbol, signal_source, owner_type,
       lifecycle_status, test_trade
FROM strategy_signals
WHERE deleted=false
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
ORDER BY created_at DESC LIMIT 10;
"""


def main():
    for label, q in [
        ("signals+orders", SQL),
        ("order summary", SQL2),
        ("blocked", SQL3),
        ("pipeline audit", SQL4),
        ("orders today total", SQL5),
        ("signal metadata", SQL6),
    ]:
        print(f"\n=== {label} ===")
        code, out = run(psql(q))
        print(out)
        if code != 0:
            print("exit", code)


if __name__ == "__main__":
    main()
