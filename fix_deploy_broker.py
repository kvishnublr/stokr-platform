import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# Link deployments to active broker account (id=4, ACTIVE, auto_reconnect)
print("=== Linking deployments to broker account id=4 ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"UPDATE deployments SET broker_account_id = 4 WHERE status='ACTIVE';\""))

# Verify
print("\n=== Verify ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, strategy_id, user_id, broker_account_id, status FROM deployments WHERE status='ACTIVE';\""))

# Verify broker account has valid token
print("\n=== Broker account 4 token status ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, status, token_expiry, access_token IS NOT NULL as has_token FROM broker_accounts WHERE id=4;\""))
