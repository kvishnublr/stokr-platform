import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout

print("=== ALL DEPLOYMENTS ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, strategy_id, user_id, status, capital, created_at FROM deployments ORDER BY id;\""))

print("\n=== ALL STRATEGIES ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, name, enabled FROM strategies ORDER BY id;\""))

print("\n=== TICK ANOMALIES magnitude overflow values ===")
print(remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT MAX(magnitude), MAX(ABS(magnitude)) FROM tick_anomalies;\""))

print("\n=== What the engine actually sees as 'active deployments' ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --since 8h 2>&1 | grep -i 'active\\|deployment' | tail -10"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else "none")
