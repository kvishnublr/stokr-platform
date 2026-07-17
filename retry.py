import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
c=lambda cmd: s.exec_command(cmd)[1].read().decode('utf-8',errors='replace').strip()

# Wait for backend
for i in range(10):
    h = c("curl -s http://localhost:8080/actuator/health")
    if h and 'UP' in h:
        print(f"Ready after {i*3}s")
        break
    time.sleep(3)

# QuickFlip
print("\n=== QuickFlip v3 ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/quickflip/run?months=3'")
time.sleep(90)
d = json.loads(stdout.read().decode('utf-8',errors='replace'))
print(f"Trades: {d.get('totalTrades')} | WR: {d.get('winRate')} | PnL: Rs.{d.get('totalNetPnl')}")
print(f"Monthly: Rs.{d.get('monthlyPnl')} | MaxDD: Rs.{d.get('maxDrawdown')}")
for pat, st in d.get('patternBreakdown',{}).items():
    if isinstance(st,dict):
        print(f"  {pat}: {st.get('totalTrades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}, Avg=Rs.{st.get('avgPerTrade')}")

# Portfolio
print("\n=== Portfolio ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(90)
d = json.loads(stdout.read().decode('utf-8',errors='replace'))
print(f"Trades: {d.get('totalTrades')} | Net: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyAvgPnl')}")
print(f"User(75%): Rs.{d.get('userProfit')} | Admin(25%): Rs.{d.get('adminFee')}")
for n,st in d.get('strategies',{}).items():
    if isinstance(st,dict): print(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
s.close()
