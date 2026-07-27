import subprocess, json

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=300)
    return r.stdout + r.stderr

# 1. Get JWT token
login_result = remote("""curl -s -X POST 'http://localhost:8081/api/auth/login' -H 'Content-Type: application/json' -d '{"email":"vishnualgo@gmail.com","password":"`$ADMIN_PASSWORD"}' """)
token = json.loads(login_result)['accessToken']

# 2. Trigger backfill
print("=== Triggering 1-month backfill ===")
result = remote(f"curl -s -X POST 'http://localhost:8081/api/admin/backfill/historical?months=1' -H 'Authorization: Bearer {token}'")
print(result[:500] if result else "no output")

# 3. Wait 30s then check status
import time
time.sleep(30)
print("\n=== Backfill status ===")
status = remote(f"curl -s 'http://localhost:8081/api/admin/backfill/status' -H 'Authorization: Bearer {token}'")
print(status[:500] if status else "no output")

