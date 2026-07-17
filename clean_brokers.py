import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

# Show all broker accounts
print("=== All broker accounts ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, broker_name, client_id, status, auto_reconnect, token_expiry, updated_at FROM broker_accounts ORDER BY id;\""))

# Keep only id=4 (ACTIVE, has token, auto_reconnect). Delete the rest.
print("\n=== Deleting stale accounts (1, 2, 3) ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"DELETE FROM broker_accounts WHERE id IN (1,2,3);\""))

# Verify
print("\n=== After cleanup ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, broker_name, client_id, status, auto_reconnect, token_expiry FROM broker_accounts;\""))
