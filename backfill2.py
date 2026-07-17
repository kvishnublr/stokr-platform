import subprocess, json

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=300)
    return r.stdout + r.stderr

# 1. Get JWT token
print("=== Getting auth token ===")
login_result = remote("""curl -s -X POST 'http://localhost:8081/api/auth/login' -H 'Content-Type: application/json' -d '{"email":"vishnualgo@gmail.com","password":"Temp@12345678"}' """)
print(login_result[:200])
try:
    token = json.loads(login_result)['token']
    print(f"Got token: {token[:20]}...")
except:
    print("Failed to get token")
    exit(1)

# 2. Trigger backfill
print("\n=== Triggering backfill (1 month) ===")
result = remote(f"curl -s -X POST 'http://localhost:8081/api/admin/backfill/historical?months=1' -H 'Authorization: Bearer {token}'")
print(result[:500] if result else "no output")
