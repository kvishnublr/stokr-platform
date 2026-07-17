import paramiko; s=paramiko.SSHClient(); s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=15)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Check which Python can connect to postgres
print("=== Su-based ===")
print(c("su - postgres -c \"psql -c 'SELECT 1'\" 2>&1"))

print("\n=== pg_hba.conf ===")
print(c("grep -v '^#' /etc/postgresql/*/main/pg_hba.conf | grep -v '^$' | head -15"))

print("\n=== Try socket ===")
for pwd in ['root123', 'wfKh8p8ISQ63VF40', 'postgres']:
    r = c(f"python3 -c \"import psycopg2; psycopg2.connect('dbname=stokr_lite user=postgres password={pwd} host=localhost'); print('OK with {pwd}')\" 2>&1")
    print(f"  {pwd}: {r[:50]}")

print("\n=== Try socket connection ===")
r = c("python3 -c \"import psycopg2; psycopg2.connect('dbname=stokr_lite user=postgres'); print('OK')\" 2>&1")
print(f"  socket: {r[:50]}")

print("\n=== Try with .pgpass ===")
r = c("ls -la /root/.pgpass 2>/dev/null; cat /root/.pgpass 2>/dev/null | head -3")
print(f"  pgpass: {r}")
s.close()
