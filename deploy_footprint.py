import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# 1. Restart Docker
print("=== Restarting Docker ===")
print(c("systemctl start docker 2>&1; sleep 3; docker info 2>&1 | head -2"))

# 2. Pull latest code
print("\n=== Git Pull ===")
print(c("cd /root/stokr-platform && git pull origin Release_v8 2>&1 | tail -3"))

# 3. Fix filename case on Linux
print("\n=== Fix filenames ===")
c("cd /root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy && [ -f institutionalfootprintstrategy.java ] && mv institutionalfootprintstrategy.java InstitutionalFootprintStrategy.java; ls Institutional* 2>/dev/null && echo OK || echo 'file exists'")
c("cd /root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration && [ -f v35__seed_institutional_footprint.sql ] && mv v35__seed_institutional_footprint.sql V35__seed_institutional_footprint.sql; ls V35* && echo OK")

# 4. Seed DB manually
print("\n=== Seed Strategy ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES ('Institutional Footprint', 'VSA Smart Money Engine', 'INSTITUTIONAL_FOOTPRINT', 'EQUITY', '{}', true, now(), now()) ON CONFLICT (name) DO NOTHING\\\"\" 2>&1"))

# 5. Force rebuild + restart
print("\n=== Docker Rebuild ===")
stdin,stdout,stderr = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose build --no-cache backend 2>&1 | tail -15")
time.sleep(180)
out = stdout.read().decode(errors='replace')
err = stderr.read().decode(errors='replace')
print(out[-600:])
if 'ERROR' in err or 'failed' in out:
    err_s = err
    print(f"BUILD ERROR: {err_s[-400:]}")

print("\n=== Starting ===")
c("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null; cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1 | tail -5")
time.sleep(35)

# 6. Health
for i in range(15):
    h = c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null")
    if h and 'UP' in h:
        print(f"Ready after {i*3}s: {h}")
        break
    time.sleep(3)

if not ('UP' in (h or '')):
    print("Backend not starting, logs:")
    print(c("docker logs stokr-lite-backend --tail 10 2>&1"))
    s.close()
    exit()

# 7. Run portfolio backtest
print("\n=== Running Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -m180 -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
time.sleep(150)
r = stdout.read().decode(errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    print(f"Total: {d.get('totalTrades')} trades | Net: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyAvgPnl')}")
    print(f"User(75%): Rs.{d.get('userProfit')} | Admin(25%): Rs.{d.get('adminFee')}")
    print(f"Annualized ROI: {d.get('annualizedRoi')}%")
    for n,st in sorted(d.get('strategies',{}).items()):
        if isinstance(st,dict) and st.get('trades',0) > 0:
            print(f"  {n}: {st['trades']}t | WR={st.get('winRate')} | PnL=Rs.{st.get('totalNetPnl')} | Avg=Rs.{st.get('avgPerTrade')} | DD=Rs.{st.get('maxDrawdown')}")
else:
    print(f"No response ({len(r)} chars): {r[:300]}")
s.close()
