import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Fix: Change timeframe from POSITIONAL to DAILY for all active strategies
print("=== Fixing strategy timeframes: POSITIONAL -> DAILY ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"UPDATE strategies SET timeframe='DAILY' WHERE id IN (15,21,23,31);\""))

# Verify
print("\n=== Verify ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, name, timeframe, enabled FROM strategies WHERE enabled=true;\""))

# Also verify the code handles the EOD flow correctly
print("\n=== ExecutionEngine EOD flow for daily strategies ===")
print(remote("sed -n '60,80p' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/ExecutionEngine.java"))
