import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Pull, rebuild, restart
script = """
cd /root/stokr-platform && git pull origin Release_v8
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null
cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -5
sleep 30
echo DONE
"""
stdin,stdout,stderr = s.exec_command(script)
out = stdout.read().decode('utf-8',errors='replace')
print(out[-500:])

# Wait for app to start
time.sleep(10)

# Run backtest
print("\n=== Running Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/quickflip/run?months=3' 2>&1")
time.sleep(120)
r = stdout.read().decode('utf-8',errors='replace')
d = json.loads(r)

print(f"Total Trades: {d.get('totalTrades')}")
print(f"Wins: {d.get('wins')}, Losses: {d.get('losses')}")
print(f"Win Rate: {d.get('winRate')}")
print(f"Total Net PnL: Rs.{d.get('totalNetPnl')}")
print(f"Avg per Trade: Rs.{d.get('avgPerTrade')}")
print(f"Max Drawdown: Rs.{d.get('maxDrawdown')}")
print(f"Monthly PnL: Rs.{d.get('monthlyPnl')}")

print("\n--- Per Pattern ---")
pb = d.get('patternBreakdown', {})
for pat, stats in pb.items():
    if isinstance(stats, dict):
        print(f"\n{pat}: {stats.get('totalTrades')} trades, WR={stats.get('winRate')}, PnL=Rs.{stats.get('totalNetPnl')}, Avg=Rs.{stats.get('avgPerTrade')}")

# Also run portfolio
print("\n\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
time.sleep(90)
r = stdout.read().decode('utf-8',errors='replace')
d = json.loads(r)
print(f"Total Trades: {d.get('totalTrades')}")
print(f"Total Net: Rs.{d.get('totalNetPnl')} | Monthly: Rs.{d.get('monthlyAvgPnl')}")
print(f"User (75%): Rs.{d.get('userProfit')} | Admin (25%): Rs.{d.get('adminFee')}")
print(f"User Monthly ROI: {d.get('userMonthlyRoi')}")

for name, st in d.get('strategies', {}).items():
    if isinstance(st, dict):
        print(f"  {name}: {st.get('trades')} tr, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
s.close()
