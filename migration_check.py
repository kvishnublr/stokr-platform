import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password=sys.argv[1], timeout=20)

# Check migrations
stdin, stdout, stderr = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -t -c 'SELECT version, description FROM flyway_schema_history ORDER BY version DESC LIMIT 6;'\""
)
time.sleep(2)
print("Migrations:")
print(stdout.read().decode())

# Check database state
stdin2, stdout2, stderr2 = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -t -c 'SELECT success, version FROM flyway_schema_history ORDER BY version DESC LIMIT 1;'\""
)
time.sleep(1)
print("Latest:", stdout2.read().decode().strip())

# If not V37, check why
stdin3, stdout3, stderr3 = s.exec_command(
    "ls /root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V3*.sql 2>&1"
)
time.sleep(1)
print("\nMigration files:")
print(stdout3.read().decode())

s.close()
