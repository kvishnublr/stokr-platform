import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Check broker account structure
print("=== broker_accounts full schema ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c '\\d broker_accounts'"))

# Check how EntryManager finds broker account
print("\n=== EntryManager broker lookup ===")
print(remote("grep -n 'Broker account\\|findByUserId\\|findByBroker\\|brokerAccount\\|findBroker' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/EntryManager.java | head -15"))

# Check EntryManager source for the error
print("\n=== EntryManager error context ===")
print(remote("grep -n -B3 -A3 'Broker account not found' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/EntryManager.java"))
