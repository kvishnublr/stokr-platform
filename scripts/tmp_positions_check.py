#!/usr/bin/env python3
import paramiko, json, base64

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out

def psql(sql):
    b64 = base64.b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")

if __name__ == "__main__":
    sql = f"""
\\pset format unaligned
\\pset fieldsep '|'
\\pset tuples_only on
SELECT 'OMS_LIVE_NET' as kind, symbol,
  SUM(CASE WHEN side='BUY' THEN COALESCE(filled_qty, quantity, 0) ELSE -COALESCE(filled_qty, quantity, 0) END) as net_qty,
  COUNT(*) as orders
FROM oms_orders
WHERE user_id = '{UID}' AND trading_mode = 'LIVE' AND status = 'FILLED'
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz
GROUP BY symbol
ORDER BY symbol;
"""
    print("=== OMS LIVE nets today ===")
    print(psql(sql))

    sql2 = f"""
\\pset format unaligned
\\pset fieldsep '|'
\\pset tuples_only on
SELECT symbol, side, status, trading_mode, reject_reason,
  created_at AT TIME ZONE 'Asia/Kolkata' as ist,
  COALESCE(filled_qty, quantity, 0) as qty,
  broker_external_order_id
FROM oms_orders
WHERE user_id = '{UID}' AND trading_mode = 'LIVE'
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz
ORDER BY created_at;
"""
    print("\n=== All LIVE OMS today ===")
    print(psql(sql2))

    sql3 = f"""
\\pset format unaligned
\\pset fieldsep '|'
\\pset tuples_only on
SELECT COALESCE(SUM(realized_pnl),0), COUNT(*)
FROM strategy_signals
WHERE user_id = '{UID}' AND deleted = false AND is_test_trade = false
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz;
"""
    print("\n=== Signal realized PnL today ===")
    print(psql(sql3))

    print("\n=== Recent workstation logs ===")
    print(run("docker logs stokr-api --since 2h 2>&1 | tail -5000 | grep -i workstation | tail -15 || true"))

    print("\n=== Broker truth logs ===")
    print(run("docker logs stokr-api --since 2h 2>&1 | tail -8000 | grep -i 'broker.truth\\|GHOST\\|external_exit' | tail -20 || true"))
