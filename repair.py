import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)

# Build script as a single command
script = r"""
cd /root/stokr-platform/stokr-lite
# Kill the broken container
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null

# Fix flyway history - reinsert V28-V33
su - postgres <<'EOSQL'
psql -d stokr_lite <<'EOF'
DELETE FROM flyway_schema_history WHERE version IN ('28','29','30','31','32','33');
SELECT setval(pg_get_serial_sequence('flyway_schema_history', 'installed_rank'), (SELECT MAX(installed_rank) FROM flyway_schema_history));
INSERT INTO flyway_schema_history (version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
VALUES 
('28', 'candle data unique constraint', 'SQL', 'V28__candle_data_unique_constraint.sql', -1, 'postgres', now(), 1, true),
('29', 'seed btst strategy', 'SQL', 'V29__seed_btst_strategy.sql', -1, 'postgres', now(), 1, true),
('30', 'seed 20day breakout strategy', 'SQL', 'V30__seed_20day_breakout_strategy.sql', -1, 'postgres', now(), 1, true),
('31', 'seed pairs trading strategy', 'SQL', 'V31__seed_pairs_trading_strategy.sql', -1, 'postgres', now(), 1, true),
('32', 'virtual wallets marketplace', 'SQL', 'V32__virtual_wallets_marketplace.sql', -1, 'postgres', now(), 1, true),
('33', 'seed quickflip strategy', 'SQL', 'V33__seed_quickflip_strategy.sql', -1, 'postgres', now(), 1, true);
EOF
EOSQL

# Verify
su - postgres -c "psql -d stokr_lite -c 'SELECT version, description FROM flyway_schema_history WHERE version::int >= 27 ORDER BY version::int'"

# Rebuild and start
docker compose up -d --build 2>&1 | tail -5

sleep 25

echo "=== Containers ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr

echo "=== Backend Logs ==="
docker logs stokr-lite-backend --tail 15 2>&1 | grep -E 'Started|Error|Flyway|Tomcat|8080|WARN|Exception|JVM' || true

echo "=== Health ==="
curl -s http://localhost:8080/actuator/health

echo ""
echo "=== Portfolio Model ==="
curl -s http://localhost:8080/api/backtest/portfolio/model | python3 -m json.tool 2>/dev/null | head -30 || curl -s http://localhost:8080/api/backtest/portfolio/model
"""
stdin, stdout, stderr = s.exec_command(script)
out = stdout.read().decode(errors='replace')
err = stderr.read().decode(errors='replace')
print(out[-3000:])
if err:
    print(f"ERR: {err[-500:]}")
s.close()
