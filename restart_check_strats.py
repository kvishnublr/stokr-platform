import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password=sys.argv[1], timeout=20)

# Restart backend
s.exec_command('cd /root/stokr-platform/stokr-lite && docker compose restart backend', get_pty=True)
print("Restarting backend...")
time.sleep(12)

# Check migrations
stdin, stdout, stderr = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -t -c 'SELECT version FROM flyway_schema_history ORDER BY version DESC LIMIT 3;'\"",
    get_pty=True)
time.sleep(2)
print("Migrations:", stdout.read().decode().strip())

# Check new strategies
stdin2, stdout2, stderr2 = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -t -c \\\"SELECT name, enabled FROM strategies WHERE strategy_type IN ('INSIDER_MOMENTUM','NIFTY_CALENDAR_SPREAD');\\\"\"",
    get_pty=True)
time.sleep(2)
print("New strategies:", stdout2.read().decode().strip())

# Check health
stdin3, stdout3, stderr3 = s.exec_command('curl -s http://localhost:8081/actuator/health', get_pty=True)
time.sleep(2)
print("Health:", stdout3.read().decode().strip())

# Backend logs for Flyway
stdin4, stdout4, stderr4 = s.exec_command(
    'cd /root/stokr-platform/stokr-lite && docker compose logs backend --tail 25 2>&1 | head -25',
    get_pty=True)
time.sleep(2)
print("\nBackend logs:")
print(stdout4.read().decode())

s.close()
