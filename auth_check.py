import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Check BackfillController to understand the API
print("=== BackfillController check ===")
print(c("docker exec stokr-lite-backend find /app -name 'BackfillController.class' 2>/dev/null"))
print(c("docker exec stokr-lite-backend jar tf /app/app.jar 2>/dev/null | grep -i backfill"))

# Try to get a JWT by registering/login
print("\n=== Try login ===")
r = c("curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"admin123\"}' 2>&1")
print(f"Login response: {r[:300]}")

# Try register
r = c("curl -s -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{\"username\":\"admin@stokr.in\",\"password\":\"Admin@123\",\"email\":\"admin@stokr.in\"}' 2>&1")
print(f"Register: {r[:300]}")

# Check if there's a default admin user in DB
print("\n=== Admin users in DB ===")
print(c("su - postgres -c \"psql -d stokr_lite -c 'SELECT username, role FROM auth_users LIMIT 5'\" 2>&1"))
s.close()
