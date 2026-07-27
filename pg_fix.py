import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("=== .env DB vars ===")
out = c("cat /root/stokr-platform/.env 2>/dev/null")
for line in out.split('\n'):
    if any(k in line.upper() for k in ['DB','POSTGRES','DATABASE','PASSWORD','PG']):
        print(line)

print()
print("=== pg_hba.conf ===")
print(c("su - postgres -c 'grep -v \"^#\" /etc/postgresql/*/main/pg_hba.conf | grep -v \"^$\"' 2>/dev/null || su - postgres -c 'psql -c \"SHOW hba_file\"' 2>&1"))

print()
print("=== Try changing postgres password ===")
print(c("su - postgres -c \"psql -c \\\"ALTER USER postgres PASSWORD '`$POSTGRES_PASSWORD'\\\"\" 2>&1"))

print()
print("=== Check pg_hba.conf for localhost md5 ===")
print(c("su - postgres -c 'psql -c \"SELECT * FROM pg_hba_file_rules WHERE database @> ARRAY[$$stokr_lite$$] OR database @> ARRAY[$$all$$]\"' 2>&1 | head -20"))
s.close()

