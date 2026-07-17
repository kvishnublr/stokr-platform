import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. ALL log lines from today that aren't just "Scan cycle" or "Zerodha live data"
print("=== ENGINE - ALL non-routine logs today ===")
print(remote("docker logs stokr-lite-backend --since 8h 2>&1 | grep -v 'Scan cycle: 4 active' | grep -v 'Zerodha live data: stored' | grep -v 'tick\\|WebSocket\\|Connecting\\|handshake\\|Binary\\|tick_anomal' | tail -80"))
