import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== universe_symbols structure ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"\\d universe_symbols;\""))

print("\n=== universe_symbols sample ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT * FROM universe_symbols WHERE universe_group_id = (SELECT id FROM universe_groups WHERE group_key='NIFTY_100') LIMIT 5;\""))

print("\n=== HistoricalDataBackfillService - how it loads instruments ===")
print(remote("grep -n 'instrument\\|instruments\\|kite\\|KiteConnect\\|loadInstrument' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/marketdata/HistoricalDataBackfillService.java | head -15"))

