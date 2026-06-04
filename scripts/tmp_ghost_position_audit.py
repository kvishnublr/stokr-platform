#!/usr/bin/env python3
"""Audit ghost NSE_SPIKE_DETECTION open position on prod."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=180):
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
        ("primary_trader", """
SELECT id, email, role FROM users
WHERE email ILIKE '%trader%' OR role ILIKE '%TRADER%'
ORDER BY created_at LIMIT 10;
"""),
        ("portfolio_open_all", """
SELECT pp.id, pp.user_id, u.email, pp.symbol, pp.strategy_key, pp.quantity,
       pp.side, pp.status, pp.deleted, pp.opened_at AT TIME ZONE 'Asia/Kolkata' AS opened_ist,
       pp.updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist
FROM portfolio_positions pp
LEFT JOIN users u ON u.id = pp.user_id
WHERE pp.deleted = false AND pp.quantity <> 0
ORDER BY pp.updated_at DESC;
"""),
        ("portfolio_nse_spike", """
SELECT * FROM portfolio_positions
WHERE deleted = false AND strategy_key = 'NSE_SPIKE_DETECTION'
ORDER BY updated_at DESC;
"""),
        ("portfolio_schema", """
SELECT column_name FROM information_schema.columns
WHERE table_name = 'portfolio_positions' ORDER BY ordinal_position;
"""),
        ("oms_open", """
SELECT o.id, o.symbol, o.side, o.state, o.execution_mode, o.strategy_key,
       o.created_at AT TIME ZONE 'Asia/Kolkata' AS created_ist,
       o.filled_quantity, o.quantity
FROM oms_orders o
WHERE o.deleted = false
  AND o.state IN ('FILLED','PARTIALLY_FILLED','OPEN','PENDING')
  AND o.created_at >= '2026-06-01'
ORDER BY o.created_at DESC LIMIT 20;
"""),
        ("running_signals", """
SELECT id, strategy_name, symbol, signal_type, lifecycle_state,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM strategy_signals
WHERE lifecycle_state IN ('RUNNING','OPEN','ACTIVE')
   OR (signal_type IN ('BUY','SELL') AND lifecycle_state IS NULL)
ORDER BY created_at DESC LIMIT 15;
"""),
        ("exec_configs_nse", """
SELECT strategy_key, execution_mode, live_enabled, max_positions
FROM strategy_execution_configs
WHERE strategy_key = 'NSE_SPIKE_DETECTION' AND user_id IS NULL;
"""),
    ]
    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql(sql))
