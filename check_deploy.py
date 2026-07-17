import subprocess

def query(q):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        "PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c " + repr(q)
    ], capture_output=True, text=True, timeout=20)
    print(r.stdout if r.stdout else r.stderr)

print("=== LIVE DEPLOYMENTS ===")
query("SELECT id, strategy_id, status, capital, universe_group FROM deployments WHERE status='LIVE';")

print("=== STRATEGIES ===")
query("SELECT id, name, enabled, timeframe FROM strategies ORDER BY id;")

print("=== RECENT ENGINE LOGS ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "docker logs stokr-lite-backend --since 1h 2>&1 | grep -v 'tick\\|WebSocket\\|Connecting\\|handshake' | tail -30"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else r.stderr)
