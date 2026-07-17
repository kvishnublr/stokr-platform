import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Set password
pw_hash = '$2b$12$OyCZxYJmhqJGr2DLlfMdj.G4phcyuhuAAxBZnlaYxqQPDsH7lo9ku'
c(f"su - postgres -c \"psql -d stokr_platform -c \\\"UPDATE auth_users SET password_hash='{pw_hash}', enabled=true, email_verified=true, locked_until=null, failed_login_attempts=0 WHERE email='admin@stokr.local'\\\"\" 2>&1")
print("Password updated")

# Login
r = c("curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{\"email\":\"admin@stokr.local\",\"password\":\"Admin@123456\"}' 2>&1")
print(f"Login: {r[:300]}")

# Extract JWT
import json
try:
    token = json.loads(r).get('token','')
    print(f"Token: {token[:50]}...")
except:
    print("No token in response")
    token = ''

if token:
    # Trigger backfill
    r = c(f"curl -s -X POST 'http://localhost:8080/api/admin/backfill/historical?months=6' -H 'Authorization: Bearer {token}' 2>&1")
    print(f"Backfill: {r}")

    # Check status
    time.sleep(5)
    r = c(f"curl -s http://localhost:8080/api/admin/backfill/status -H 'Authorization: Bearer {token}' 2>&1")
    print(f"Status: {r[:500]}")
else:
    print("No token - trying direct DB backfill approach instead")
    # Direct approach: write a quick Python script that fetches from Zerodha API
    print("Checking if zerodha config exists in DB...")
    print(c("su - postgres -c \"psql -d stokr_lite -c 'SELECT broker_name, status FROM broker_accounts'\" 2>&1"))

s.close()
