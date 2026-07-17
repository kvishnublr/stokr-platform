import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

print("Rebuilding...")
stdin,stdout,stderr = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose build backend 2>&1 | tail -5")
time.sleep(120)
out = stdout.read().decode('utf-8',errors='replace')
err = stderr.read().decode('utf-8',errors='replace')
print(out[-400:])
if 'ERROR' in out or 'error' in out or 'failed' in err:
    print(f"Build issue: {out[-300:]} {err[-300:]}")
    # Try to compile directly on host
    print("Trying direct Maven build...")
    print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep -E 'ERROR|BUILD' | head -5"))
    exit(1)

print("\nStarting...")
c("docker rm -f stokr-lite-backend 2>/dev/null; cd /root/stokr-platform/stokr-lite && docker compose up -d backend 2>&1 | tail -3")
time.sleep(35)

for i in range(12):
    h = c("curl -s http://localhost:8080/actuator/health")
    if h and 'UP' in h:
        print(f"Ready at {i*3}s: {h}")
        break
    time.sleep(3)

# Backtest
print("\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(120)
r = stdout.read().decode('utf-8',errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    print(f"Total: {d.get('totalTrades')}t, Net=Rs.{d.get('totalNetPnl')}, Monthly=Rs.{d.get('monthlyAvgPnl')}")
    for n,st in d.get('strategies',{}).items():
        if isinstance(st,dict): print(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
else:
    print("No backtest response. Logs:")
    print(c("docker logs stokr-lite-backend 2>&1 | tail -10"))
s.close()
