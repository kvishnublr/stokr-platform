import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password=sys.argv[1], timeout=20)

# Run V36 and V37 SQL directly
for v, desc in [(36, 'NIFTY Calendar Spread'), (37, 'Insider Momentum')]:
    sql_file = f"/root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V{v}__seed_nifty_calendar_spread.sql" if v == 36 else f"/root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V{v}__seed_insider_momentum.sql"
    
    stdin, stdout, stderr = s.exec_command(
        f'su - postgres -c "psql -d stokr_lite -f {sql_file} 2>&1"',
        get_pty=True)
    time.sleep(2)
    out = stdout.read().decode().strip()
    err = stderr.read().decode().strip()
    print(f"V{v} ({desc}):", out if out else "OK" if not err else err)
    
    # Also insert into flyway history
    stdin2, stdout2, stderr2 = s.exec_command(
        f'su - postgres -c "psql -d stokr_lite -c \\\"INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) SELECT COALESCE(MAX(installed_rank),0)+1, \'{v}\', \'{desc}\', \'SQL\', \'V{v}__seed.sql\', 0, \'postgres\', NOW(), 0, true FROM flyway_schema_history ON CONFLICT (version) DO NOTHING;\\\" 2>&1"',
        get_pty=True)
    time.sleep(1)

# Verify
stdin3, stdout3, stderr3 = s.exec_command(
    "su - postgres -c \"psql -d stokr_lite -t -c 'SELECT name, enabled FROM strategies WHERE strategy_type IN (''''INSIDER_MOMENTUM'''',''''NIFTY_CALENDAR_SPREAD'''');'\"",
    get_pty=True)
time.sleep(2)
print("\nNew strategies:", stdout3.read().decode().strip())

s.close()
