import subprocess

def query(q):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c " + repr(q)
    ], capture_output=True, text=True, timeout=20)
    print(r.stdout if r.stdout else r.stderr)

print("=== TODAY SIGNALS ===")
query("SELECT id, symbol, strategy_id, side, entry_price, confidence, status, created_at FROM strategy_signals WHERE created_at >= '2026-07-13' ORDER BY created_at DESC;")

print("\n=== TODAY ORDERS ===")
query("SELECT id, symbol, side, quantity, order_type, status, broker_order_id, created_at FROM orders WHERE created_at >= '2026-07-13' ORDER BY created_at DESC;")

print("\n=== TODAY TRADES ===")
query("SELECT id, symbol, side, entry_price, exit_price, realized_pnl, status, created_at FROM trades WHERE created_at >= '2026-07-13' ORDER BY created_at DESC;")

print("\n=== SCAN CYCLES TODAY (last 20) ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --since 8h 2>&1 | grep -i 'Scan cycle' | tail -20"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else r.stderr)

