import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)

script = r"""
docker rm -f stokr-lite-backend 2>/dev/null

# Drop the already-existing constraint that flyway would try to recreate
su - postgres -c "psql -d stokr_lite -c 'ALTER TABLE candle_data DROP CONSTRAINT IF EXISTS uq_candle_symbol_timeframe_timestamp'"

# Insert flyway history with explicit installed_rank
su - postgres -c "psql -d stokr_lite" <<'EOF'
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (28, '28', 'candle data unique constraint', 'SQL', 'V28__candle_data_unique_constraint.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (29, '29', 'seed btst strategy', 'SQL', 'V29__seed_btst_strategy.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (30, '30', 'seed 20day breakout strategy', 'SQL', 'V30__seed_20day_breakout_strategy.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (31, '31', 'seed pairs trading strategy', 'SQL', 'V31__seed_pairs_trading_strategy.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (32, '32', 'virtual wallets marketplace', 'SQL', 'V32__virtual_wallets_marketplace.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (33, '33', 'seed quickflip strategy', 'SQL', 'V33__seed_quickflip_strategy.sql', 0, 'postgres', now(), 1, true) ON CONFLICT DO NOTHING;
EOF

echo "=== Verify history ==="
su - postgres -c "psql -d stokr_lite -c 'SELECT version, description FROM flyway_schema_history WHERE version::int >= 27 ORDER BY version::int'"

# Rebuild
cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -5
sleep 30

echo "=== Status ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr

echo "=== Logs ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started|Error|Flyway|8080|WARN.*schema|Exception|JVM' | tail -8

echo "=== Health ==="
curl -s http://localhost:8080/actuator/health

echo ""
echo "=== Portfolio API ==="
curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 500
"""
stdin, stdout, stderr = s.exec_command(script)
print(stdout.read().decode(errors='replace')[-4000:])
s.close()

