import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)

script = r"""
# Kill container
docker rm -f stokr-lite-backend 2>/dev/null

# Fix flyway history with explicit installed_rank
su - postgres -c "psql -d stokr_lite" <<'EOF'
ALTER TABLE flyway_schema_history ALTER COLUMN installed_rank DROP NOT NULL;
INSERT INTO flyway_schema_history (version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT v, d, 'SQL', s, -1, 'postgres', now(), 1, true
FROM (VALUES 
  ('28','candle data unique constraint','V28__candle_data_unique_constraint.sql'),
  ('29','seed btst strategy','V29__seed_btst_strategy.sql'),
  ('30','seed 20day breakout strategy','V30__seed_20day_breakout_strategy.sql'),
  ('31','seed pairs trading strategy','V31__seed_pairs_trading_strategy.sql'),
  ('32','virtual wallets marketplace','V32__virtual_wallets_marketplace.sql'),
  ('33','seed quickflip strategy','V33__seed_quickflip_strategy.sql')
) AS t(v,d,s)
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = t.v);

UPDATE flyway_schema_history SET installed_rank = version::int WHERE installed_rank IS NULL;
ALTER TABLE flyway_schema_history ALTER COLUMN installed_rank SET NOT NULL;
EOF

# Verify
echo "=== Flyway versions ==="
su - postgres -c "psql -d stokr_lite -c 'SELECT version, description, success FROM flyway_schema_history WHERE version::int >= 27 ORDER BY version::int'"

# Rebuild and start  
cd /root/stokr-platform/stokr-lite
docker compose up -d --build 2>&1 | tail -5
sleep 30

echo "=== Backend ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr

echo "=== Logs ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started|Error|Flyway|8080|WARN.*schema|Exception' | tail -5

echo "=== Health ==="
curl -s http://localhost:8080/actuator/health

echo ""
echo "=== API Test ==="
curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 500
"""
stdin, stdout, stderr = s.exec_command(script)
print(stdout.read().decode(errors='replace')[-3000:])
err = stderr.read().decode(errors='replace')
if err.strip(): print(f"ERR: {err[-500:]}")
s.close()

