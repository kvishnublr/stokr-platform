import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Does StrategyService cache timeframe or read from DB each time?
print("=== StrategyService - does it cache? ===")
print(remote("grep -n 'cache\\|Cache\\|timeframe\\|getTimeframe\\|getStrategy' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/StrategyService.java | head -20"))

print("\n=== Strategy entity - timeframe field ===")
print(remote("grep -n 'timeframe\\|Timeframe' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/Strategy.java | head -10"))

# Check if there's daily candle data for today's symbols
print("\n=== Daily candle data freshness ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, MAX(timestamp) as latest FROM candle_data WHERE timeframe='daily' GROUP BY symbol ORDER BY latest DESC LIMIT 5;\""))

# Check what symbols are in NIFTY_100 universe
print("\n=== Strategy universe mappings ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT sum.* FROM strategy_universe_mapping sum JOIN universe_groups ug ON sum.universe_group_id = ug.id WHERE sum.strategy_id IN (15,21,23,31);\""))
