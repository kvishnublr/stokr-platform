import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Fix filenames on server
print("Fixing filenames...")
c("cd /root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy && mv momentumsurgestrategy.java MomentumSurgeStrategy.java 2>/dev/null; ls MomentumSurgeStrategy.java && echo OK || echo FAIL")
c("cd /root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration && mv v34__seed_momentum_surge_strategy.sql V34__seed_momentum_surge_strategy.sql 2>/dev/null; ls V34__seed_momentum_surge_strategy.sql && echo OK || echo FAIL")

# Rebuild
print("Building...")
stdin,stdout,stderr = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose build --no-cache backend 2>&1 | tail -10")
time.sleep(180)
out = stdout.read().decode('utf-8',errors='replace')
err = stderr.read().decode('utf-8',errors='replace')
print(out[-500:])
if err: print(f"STDERR: {err[-300:]}")

# Start
print("\nStarting...")
print(c("cd /root/stokr-platform/stokr-lite && docker compose up -d backend 2>&1"))
time.sleep(35)

# Health
for i in range(10):
    h = c("curl -s http://localhost:8080/actuator/health")
    if h and 'UP' in h:
        print(f"Ready! Health: {h}")
        break
    time.sleep(3)

# Portfolio backtest  
print("\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(120)
r = stdout.read().decode('utf-8',errors='replace')
if r.strip():
    d = json.loads(r)
    print(f"Total: {d.get('totalTrades')} trades, Net=Rs.{d.get('totalNetPnl')}, Monthly=Rs.{d.get('monthlyAvgPnl')}")
    for n,st in d.get('strategies',{}).items():
        if isinstance(st,dict): print(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
else:
    print("Empty response - checking container logs")
    print(c("docker logs stokr-lite-backend 2>&1 | grep -E 'ERROR|Started|Exception' | tail -5"))
s.close()
