import paramiko,time,json,sys
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
c=lambda cmd: s.exec_command(cmd)[1].read().decode('utf-8',errors='replace').strip()

print("=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(120)
r = stdout.read().decode('utf-8',errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    sys.stdout.write(f"Total: {d.get('totalTrades')} trades\n")
    sys.stdout.write(f"Net PnL: Rs.{d.get('totalNetPnl')}\n")
    sys.stdout.write(f"Monthly: Rs.{d.get('monthlyAvgPnl')}\n")
    sys.stdout.write(f"User(75%): Rs.{d.get('userProfit')} | Admin(25%): Rs.{d.get('adminFee')}\n")
    sys.stdout.write(f"User ROI: {d.get('userMonthlyRoi')}\n")
    sys.stdout.write("\n")
    for n,st in d.get('strategies',{}).items():
        if isinstance(st,dict): 
            sys.stdout.write(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}\n")
    sys.stdout.flush()
else:
    print(f"No valid response (len={len(r)}): {r[:200]}")

# Also get model to confirm Momentum Surge is registered
print("\n=== Portfolio Model ===")
r = c("curl -s http://localhost:8080/api/backtest/portfolio/model")
print(r[:800])
s.close()
