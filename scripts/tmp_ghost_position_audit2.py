#!/usr/bin/env python3
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
        ("app_users", """
SELECT id, email, role FROM app_users ORDER BY created_at LIMIT 15;
"""),
        ("primary_trader", """
SELECT id, email FROM app_users WHERE email ILIKE '%primary%' OR email ILIKE '%trader%' LIMIT 5;
"""),
        ("all_open_positions", """
SELECT id, user_id, symbol, quantity, avg_price, strategy_key, deleted,
       updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist
FROM portfolio_positions
WHERE deleted = false AND quantity <> 0
ORDER BY updated_at DESC;
"""),
        ("nse_spike_orders", """
SELECT id, symbol, side, state, execution_mode, strategy_key, user_id,
       created_at AT TIME ZONE 'Asia/Kolkata' AS ist
FROM oms_orders
WHERE strategy_key = 'NSE_SPIKE_DETECTION' AND deleted = false
ORDER BY created_at DESC LIMIT 10;
"""),
        ("broker_accounts", """
SELECT user_id, broker, connection_status, updated_at AT TIME ZONE 'Asia/Kolkata'
FROM broker_accounts WHERE deleted = false ORDER BY updated_at DESC LIMIT 5;
"""),
    ]
    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql(sql))
