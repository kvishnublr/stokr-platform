import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. Verify spam cleaned
print("=== Signals today after cleanup ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT count(*) as total, strategy_id, status FROM strategy_signals WHERE created_at >= '2026-07-13' GROUP BY strategy_id, status;\""))

# 2. Verify strategy timeframes now DAILY
print("\n=== Strategy timeframes ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, name, timeframe, enabled FROM strategies WHERE enabled=true;\""))

# 3. Check daily candle data - do we have enough for the strategies?
print("\n=== Daily candle count per symbol (sample) ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, count(*) as candles, min(timestamp) as earliest, max(timestamp) as latest FROM candle_data WHERE timeframe='daily' GROUP BY symbol HAVING count(*) > 0 ORDER BY latest DESC LIMIT 10;\""))

# 4. Check if daily candles exist for NIFTY_100 symbols
print("\n=== NIFTY_100 symbols with daily data ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT count(DISTINCT symbol) as symbols_with_daily FROM candle_data WHERE timeframe='daily';\""))

# 5. Current time
print("\n=== Current IST time ===")
print(remote("TZ=Asia/Kolkata date '+%H:%M:%S'"))
