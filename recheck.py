import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
c=lambda cmd: s.exec_command(cmd)[1].read().decode('utf-8',errors='replace').strip()

script = "cd /root/stokr-platform && git pull origin Release_v8 && docker rm -f stokr-lite-backend 2>/dev/null; cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -5"
stdin,stdout,stderr = s.exec_command(script)
time.sleep(60)
print(stdout.read().decode('utf-8',errors='replace')[-500:])

# Health check
time.sleep(10)
print("Health:", c("curl -s http://localhost:8080/actuator/health"))

# QuickFlip backtest
print("\n=== QuickFlip Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/quickflip/run?months=3'")
time.sleep(90)
d = json.loads(stdout.read().decode('utf-8',errors='replace'))
print(f"Trades: {d.get('totalTrades')} | WR: {d.get('winRate')} | PnL: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyPnl')}")
for pat, st in d.get('patternBreakdown',{}).items():
    if isinstance(st,dict):
        print(f"  {pat}: {st.get('totalTrades')} trades, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")

# Portfolio
print("\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(90)
d = json.loads(stdout.read().decode('utf-8',errors='replace'))
print(f"Trades: {d.get('totalTrades')} | Total Net: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyAvgPnl')}")
print(f"User(75%): Rs.{d.get('userProfit')} | Admin(25%): Rs.{d.get('adminFee')} | ROI: {d.get('userMonthlyRoi')}")
for name, st in d.get('strategies',{}).items():
    if isinstance(st,dict): print(f"  {name}: {st.get('trades')} tr, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
s.close()
