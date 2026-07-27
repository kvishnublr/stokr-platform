import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. Check signals around 15:10
print("=== Signals generated around 15:10 ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, deployment_id, strategy_id, symbol, side, status, entry_price, created_at FROM strategy_signals WHERE created_at >= '2026-07-13 15:05:00' ORDER BY created_at DESC;\""))

# 2. Check ALL signals today
print("\n=== ALL signals today by strategy ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT strategy_id, status, count(*) FROM strategy_signals WHERE created_at >= '2026-07-13' GROUP BY strategy_id, status;\""))

# 3. Check broker accounts
print("\n=== Broker accounts ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, broker_name, client_id, status, auto_reconnect FROM broker_accounts;\""))

# 4. Check deployments and which broker user_id they link to
print("\n=== Deployments ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, strategy_id, user_id, status, capital FROM deployments WHERE status='ACTIVE';\""))

