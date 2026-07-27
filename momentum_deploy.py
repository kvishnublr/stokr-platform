import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Pull, fix filenames, rebuild
script = """
cd /root/stokr-platform && git pull origin Release_v8

# Fix lowercase filenames for Linux
cd stokr-lite/backend/src/main/java/com/stokr/strategy
[ -f momentumsurgestrategy.java ] && mv momentumsurgestrategy.java MomentumSurgeStrategy.java
cd /root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration
[ -f v34__seed_momentum_surge_strategy.sql ] && mv v34__seed_momentum_surge_strategy.sql V34__seed_momentum_surge_strategy.sql

# Seed V34 manually (Flyway won't run it since DB already at V33 baseline)
su - postgres -c "psql -d stokr_lite -c \\\"INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES ('Momentum Surge', '5-condition confluence on 5-min candles', 'MOMENTUM_SURGE', 'EQUITY', '{}', true, now(), now()) ON CONFLICT (name) DO NOTHING\\\"\" 2>&1

# Rebuild & restart
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null
cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -5
sleep 35
echo "READY"
curl -s http://localhost:8080/actuator/health
"""
stdin,stdout,stderr = s.exec_command(script)
time.sleep(80)
print(stdout.read().decode('utf-8',errors='replace')[-1500:])

# Wait for app
for i in range(10):
    h = c("curl -s http://localhost:8080/actuator/health")
    if h and 'UP' in h: break
    time.sleep(3)

# Run Momentum Surge backtest directly
print("\n=== Momentum Surge Backtest ===")
# Write a quick Python backtest since we need it fast
backtest_script = r'''
import psycopg2, json, sys
conn = psycopg2.connect("dbname=stokr_lite user=postgres password=root123 host=localhost")
cur = conn.cursor()
cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min' LIMIT 50")
symbols = [r[0] for r in cur.fetchall()]

# Simple backtest: for each day, aggregate 5-min candles, check conditions
# We'll use the backtest endpoint
conn.close()
print("test")
'''
# Use the API
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
time.sleep(120)
d = json.loads(stdout.read().decode('utf-8',errors='replace'))
print(f"Trades: {d.get('totalTrades')} | Net: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyAvgPnl')}")
for n, st in d.get('strategies',{}).items():
    if isinstance(st,dict): 
        print(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")

s.close()

