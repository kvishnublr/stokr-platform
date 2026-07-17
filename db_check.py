import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

i, o, e = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -c \\\"SELECT name, strategy_type, enabled FROM strategies ORDER BY id;\\\"\" 2>&1",
    get_pty=True)
time.sleep(3)
print(o.read(4096).decode('utf-8', errors='replace'))

# Check migration ran
i2, o2, e2 = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -c \\\"SELECT version, description FROM flyway_schema_history ORDER BY version DESC LIMIT 5;\\\"\" 2>&1",
    get_pty=True)
time.sleep(2)
print(o2.read(4096).decode('utf-8', errors='replace'))

s.close()
