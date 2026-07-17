import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Check .env
print("=== .env Zerodha keys ===")
for line in c('cat /root/stokr-platform/.env 2>/dev/null').split('\n'):
    if any(k in line.upper() for k in ['ZERODHA','API_KEY','API_SECRET','KITE','TOKEN']):
        print(line)

# Check broker_accounts structure
print("\n=== broker_accounts structure ===")
print(c("su - postgres -c \"psql -d stokr_lite -c '\\d broker_accounts'\" 2>&1"))

# Check if there are config maps or secrets in container
print("\n=== Container config check ===")
print(c("docker exec stokr-lite-backend ls /app/BOOT-INF/classes/ 2>/dev/null | head -20"))
print(c("docker exec stokr-lite-backend cat /app/BOOT-INF/classes/application.properties 2>/dev/null | head -30"))
s.close()
