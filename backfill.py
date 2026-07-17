import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=300)
    return r.stdout + r.stderr

# Try backfill endpoint - small range to just get Jul 9-13
print("=== Triggering historical backfill (1 month to refresh recent data) ===")
result = remote("curl -s -X POST 'http://localhost:8081/api/admin/backfill/historical?months=1' -H 'Content-Type: application/x-www-form-urlencoded'")
print(result[:500] if result else "no output")
