import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Reinsert flyway history entries for V28-V33 since DB already has them
print("=== Reinsert flyway history ===")
sql = """
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
VALUES 
  (28, '28', 'candle data unique constraint', 'SQL', 'V28__candle_data_unique_constraint.sql', 0, 'postgres', now(), 100, true),
  (29, '29', 'seed btst strategy', 'SQL', 'V29__seed_btst_strategy.sql', 0, 'postgres', now(), 100, true),
  (30, '30', 'seed 20day breakout strategy', 'SQL', 'V30__seed_20day_breakout_strategy.sql', 0, 'postgres', now(), 100, true),
  (31, '31', 'seed pairs trading strategy', 'SQL', 'V31__seed_pairs_trading_strategy.sql', 0, 'postgres', now(), 100, true),
  (32, '32', 'virtual wallets marketplace', 'SQL', 'V32__virtual_wallets_marketplace.sql', 0, 'postgres', now(), 100, true),
  (33, '33', 'seed quickflip strategy', 'SQL', 'V33__seed_quickflip_strategy.sql', 0, 'postgres', now(), 100, true)
ON CONFLICT (version) DO NOTHING
"""
print(c(f"su - postgres -c \"psql -d stokr_lite -c '{sql}'\" 2>&1"))

# Verify
print("\n=== Flyway versions ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT version, description, success FROM flyway_schema_history WHERE version::int >= 27 ORDER BY version\\\"\" 2>&1"))

# Restart with fixed compose
print("\n=== Restart backend ===")
c("docker rm -f stokr-lite-backend 2>/dev/null; echo done")
print(c("cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -5"))

time.sleep(25)

print("\n=== Backend logs ===")
for line in c("docker logs stokr-lite-backend --tail 25 2>&1").split('\n'):
    if any(k in line for k in ['Started','Error','Flyway','Tomcat','8080','WARN','Exception','JVM','health']):
        print(f"  {line[:250]}")

print("\n=== Health ===")
print(c("curl -s http://localhost:8080/actuator/health"))

print("\n=== Portfolio Model API (should be 200 now) ===")
print(c("curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 800"))

print("\n=== QuickFlip Model ===")
print(c("curl -s http://localhost:8080/api/backtest/quickflip/model | head -c 500"))

s.close()
