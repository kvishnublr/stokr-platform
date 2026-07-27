import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout

print("=== DEPLOYMENTS ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, strategy_id, status, capital FROM deployments WHERE status='LIVE';\""))

print("=== STRATEGY UNIVERSE ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, name, universe_group FROM strategies WHERE id IN (15,21,23,31);\""))

print("=== LAST SIGNAL EVER ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, symbol, strategy_id, status, created_at FROM strategy_signals ORDER BY created_at DESC LIMIT 5;\""))

print("=== ENGINE LOGS (any errors or warnings today) ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --since 8h 2>&1 | grep -i 'ERROR\\|WARN\\|exception\\|no.*candle\\|no.*data' | tail -15"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else "none")

print("=== LATEST 1MIN CANDLES FOR A NIFTY_50 STOCK ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, timestamp, close, volume FROM candle_data WHERE symbol='RELIANCE' AND timeframe='1min' ORDER BY timestamp DESC LIMIT 5;\""))

