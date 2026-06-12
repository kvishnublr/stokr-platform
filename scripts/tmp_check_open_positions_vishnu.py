#!/usr/bin/env python3
"""Diagnose why Vishnu broker positions are still open."""
import json
import urllib.error
import urllib.request

import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"


def ssh(c, cmd):
    _, o, e = c.exec_command(cmd, timeout=120)
    return (o.read() + e.read()).decode("utf-8", "replace")


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    print("=== 1. LIVE filled entry orders still open (no outcome-exit after) ===")
    sql1 = f"""
SELECT o.symbol, o.side, o.quantity, o.state, o.strategy_key,
       o.created_at AT TIME ZONE 'Asia/Kolkata' AS entry_ist,
       (SELECT count(*) FROM oms_orders x WHERE x.deleted=false
          AND x.idempotency_key LIKE 'outcome-exit:%'
          AND x.user_id=o.user_id AND x.symbol=o.symbol
          AND x.created_at > o.created_at) AS exits_after
FROM oms_orders o
WHERE o.deleted=false AND o.user_id='{UID}'
  AND o.execution_mode='LIVE' AND o.state='FILLED'
  AND (o.idempotency_key IS NULL OR o.idempotency_key NOT LIKE 'outcome-exit:%')
ORDER BY o.created_at DESC LIMIT 20;
"""
    print(ssh(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql1.strip()}"'))

    print("\n=== 2. Outcome-exit orders by execution_mode (today) ===")
    sql2 = f"""
SELECT execution_mode, state, count(*), string_agg(DISTINCT symbol, ', ' ORDER BY symbol) AS symbols
FROM oms_orders
WHERE deleted=false AND user_id='{UID}'
  AND idempotency_key LIKE 'outcome-exit:%'
  AND created_at > NOW() - INTERVAL '24 hours'
GROUP BY execution_mode, state;
"""
    print(ssh(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql2.strip()}"'))

    print("\n=== 3. Paired PAPER/LIVE entry legs (recent) ===")
    sql3 = f"""
SELECT paper.symbol, paper.execution_mode AS paper_mode, paper.state AS paper_state,
       live.id IS NOT NULL AS has_live_pair, live.state AS live_state,
       paper.paired_order_id
FROM oms_orders paper
LEFT JOIN oms_orders live ON live.id = paper.paired_order_id
WHERE paper.deleted=false AND paper.user_id='{UID}'
  AND paper.signal_id IS NOT NULL
  AND paper.created_at > NOW() - INTERVAL '72 hours'
ORDER BY paper.created_at DESC LIMIT 15;
"""
    print(ssh(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql3.strip()}"'))

    print("\n=== 4. portfolio_positions (OMS cache) non-zero ===")
    sql4 = f"""
SELECT symbol, quantity, avg_price, updated_at AT TIME ZONE 'Asia/Kolkata'
FROM portfolio_positions WHERE user_id='{UID}' AND deleted=false AND quantity != 0
ORDER BY updated_at DESC;
"""
    print(ssh(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql4.strip()}"'))

    print("\n=== 5. API broker truth via localhost (admin login) ===")
    login_cmd = """curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"principal":"admin@stokr.local","password":"admin123"}'"""
    login_raw = ssh(c, login_cmd)
    try:
        token = json.loads(login_raw).get("data", {}).get("accessToken")
    except json.JSONDecodeError:
        token = None
        print("login failed:", login_raw[:300])
    if token:
        ws = ssh(c, f"""curl -s 'http://127.0.0.1:8080/api/trader/terminal/workstation?userId={UID}' -H 'Authorization: Bearer {token}'""")
        try:
            data = json.loads(ws).get("data", {})
            positions = data.get("positions") or data.get("brokerPositions") or []
            print("workstation positions count:", len(positions) if isinstance(positions, list) else type(positions))
            if isinstance(positions, list):
                for p in positions[:15]:
                    print(" ", p.get("symbol"), p.get("quantity") or p.get("brokerQty"), p.get("source"))
            exposure = data.get("exposure") or {}
            print("exposure keys:", list(exposure.keys())[:8] if isinstance(exposure, dict) else exposure)
        except json.JSONDecodeError:
            print(ws[:800])

    print("\n=== 6. Recent skip_flat / LIVE exit logs for user ===")
    print(ssh(c, f"docker logs stokr-api --since 6h 2>&1 | grep -E '{UID}|outcome_exit\\.(placed|failed|skip_flat)' | tail -25"))

    c.close()


if __name__ == "__main__":
    main()
