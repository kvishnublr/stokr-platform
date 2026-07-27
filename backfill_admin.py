import subprocess, json, time

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=600)
    return r.stdout + r.stderr

# 1. Get ADMIN JWT token
login_result = remote("""curl -s -X POST 'http://localhost:8081/api/auth/login' -H 'Content-Type: application/json' -d '{"email":"admin@stokr.in","password":"`$ADMIN_PASSWORD"}' """)
print(f"Login: {login_result[:200]}")
token = json.loads(login_result)['accessToken']

# 2. Trigger backfill
print("\n=== Triggering 1-month backfill ===")
result = remote(f"curl -s -X POST 'http://localhost:8081/api/admin/backfill/historical?months=1' -H 'Authorization: Bearer {token}'")
print(result[:500] if result else "no output")

# 3. Poll status
for i in range(12):
    time.sleep(30)
    status = remote(f"curl -s 'http://localhost:8081/api/admin/backfill/status' -H 'Authorization: Bearer {token}'")
    print(f"\n[{i*30}s] {status[:300]}")
    if "completed" in status.lower() or "DONE" in status or '"progress":100' in status:
        break

