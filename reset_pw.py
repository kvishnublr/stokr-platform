import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Find user in stokr_platform
print("=== Find admin user ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT id, email FROM auth_users\\\"\" 2>&1"))

# Check password column
print("\n=== Table schema ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"\\\d auth_users\\\"\" 2>&1 | head -20"))

# Check if password reset tokens exist
print("\n=== Auth columns ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT column_name FROM information_schema.columns WHERE table_name='auth_users'\\\"\" 2>&1"))
s.close()
