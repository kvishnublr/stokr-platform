import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

# 1. Create log directory and test cron
print("=== Creating log directory ===")
print(remote("mkdir -p /var/log && touch /var/log/stokr-token-refresh.log && chmod 644 /var/log/stokr-token-refresh.log"))

# 2. Check crontab has the right PATH
print("\n=== Current crontab ===")
print(remote("crontab -l"))

# 3. Fix tick_anomalies magnitude precision
print("\n=== Fixing tick_anomalies magnitude precision ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"ALTER TABLE tick_anomalies ALTER COLUMN magnitude TYPE NUMERIC(18,4);\""))
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"ALTER TABLE tick_anomalies ALTER COLUMN vwap_deviation TYPE NUMERIC(18,4);\""))

# 4. Verify
print("\n=== Verify new precision ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT column_name, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='tick_anomalies' AND numeric_precision IS NOT NULL;\""))
