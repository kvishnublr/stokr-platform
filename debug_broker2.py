import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Search for the error message
print("=== Where 'Broker account not found' is thrown ===")
print(remote("grep -rn 'Broker account not found' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/ 2>/dev/null"))

# Check if deployments have broker_account_id set
print("\n=== Deployments broker_account_id ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, strategy_id, user_id, broker_account_id FROM deployments WHERE status='ACTIVE';\""))
