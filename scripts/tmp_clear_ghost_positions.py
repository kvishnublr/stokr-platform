#!/usr/bin/env python3
"""Clear ghost portfolio positions and verify risk dashboard counts."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=3600):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").strip()


def psql(sql):
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")


if __name__ == "__main__":
    print("=== BEFORE ===")
    print(psql("""
SELECT id, user_id, symbol, quantity, avg_price, strategy_key
FROM portfolio_positions WHERE deleted=false AND quantity <> 0;
"""))

    print("\n=== CLEAR GHOSTS ===")
    print(psql("""
UPDATE portfolio_positions SET
  quantity = 0, avg_price = 0, unrealized_pnl = 0, mtm_price = NULL, deleted = true, updated_at = NOW()
WHERE deleted = false AND quantity <> 0
  AND id IN (
    '02fc8e63-39b0-4269-b55b-3fd8629ae167',
    '856a458f-5bde-452a-8cfd-f489706c4cd9'
  );
"""))

    print("\n=== AFTER ===")
    print(psql("""
SELECT id, user_id, symbol, quantity, strategy_key, deleted
FROM portfolio_positions WHERE deleted=false AND quantity <> 0;
"""))

    print("\n=== per strategy open counts ===")
    print(psql("""
SELECT strategy_key, COUNT(*) AS open_positions
FROM portfolio_positions
WHERE deleted=false AND is_simulation=false AND quantity <> 0
GROUP BY strategy_key;
"""))

    print("\n=== git ===")
    print(run(f"cd {BASE} && git rev-parse --short HEAD"))
