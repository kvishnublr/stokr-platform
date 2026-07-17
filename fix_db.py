import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Check both DBs
print("=== stokr_lite tables ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename\\\"\" 2>&1 | head -20"))

print("\n=== stokr_lite strategies ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, name, strategy_type FROM strategies ORDER BY id\\\"\" 2>&1"))

print("\n=== stokr_platform tables ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename\\\"\" 2>&1 | head -20"))

print("\n=== stokr_lite flyway history ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5\\\"\" 2>&1"))

print("\n=== stokr_platform flyway history ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5\\\"\" 2>&1"))

# Fix compose to use stokr_lite
print("\n=== Fixing compose to stokr_lite ===")
c("""sed -i 's|stokr_platform|stokr_lite|g' /root/stokr-platform/stokr-lite/docker-compose.yml""")
print(c("grep SPRING_DATASOURCE_URL /root/stokr-platform/stokr-lite/docker-compose.yml"))

# Reset flyway baseline on stokr_lite to 27 (last legit migration)
print("\n=== Reset flyway baseline ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"DELETE FROM flyway_schema_history WHERE version > '27'\\\"\" 2>&1"))

# Ensure QuickFlip migration exists
print(c("ls -la /root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V33* 2>/dev/null || echo 'V33 NOT FOUND'"))

s.close()
