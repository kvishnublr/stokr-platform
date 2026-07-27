import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout

print("=== Full tick_anomalies schema ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"\\d tick_anomalies\""))

print("\n=== Check the problematic value ===")
print(remote("PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, symbol, magnitude, vwap_deviation, price_at_event FROM tick_anomalies WHERE magnitude > 100000 ORDER BY created_at DESC LIMIT 5;\""))

