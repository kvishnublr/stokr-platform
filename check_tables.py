import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

print("=== zerodha_instruments count ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT count(*) FROM zerodha_instruments;\""))

print("\n=== zerodha_instruments sample ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT * FROM zerodha_instruments LIMIT 3;\""))

print("\n=== universe_group_members table ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"\\d universe_group_members;\""))

print("\n=== universe_groups ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT * FROM universe_groups;\""))

print("\n=== How existing daily candles were loaded ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT symbol, timestamp, close FROM candle_data WHERE timeframe='daily' LIMIT 3;\""))
