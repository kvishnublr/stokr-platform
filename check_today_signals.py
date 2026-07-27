import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

print("=== ALL SIGNALS TODAY ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, deployment_id, strategy_id, symbol, side, status, entry_price, confidence, created_at FROM strategy_signals WHERE created_at >= '2026-07-13' ORDER BY strategy_id, created_at DESC;\""))

print("\n=== COUNT BY STRATEGY ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT strategy_id, status, count(*) FROM strategy_signals WHERE created_at >= '2026-07-13' GROUP BY strategy_id, status;\""))

