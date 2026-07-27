import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

print("=== Running Portfolio Backtest (3 months data-driven) ===")
# This will take a while - it reads all candle data and backtests 4 strategies
cmd = "curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1"
stdin,stdout,stderr = s.exec_command(cmd); time.sleep(60)
r = stdout.read().decode('utf-8',errors='replace')

# Try to parse and display
try:
    import json
    d = json.loads(r)
    print(f"\nTotal Net P&L: Rs.{d.get('totalNetPnl','N/A')}")
    print(f"Monthly Avg: Rs.{d.get('monthlyAvgPnl','N/A')}")
    print(f"User Profit (75%): Rs.{d.get('userProfit','N/A')}")
    print(f"Admin Fee (25%): Rs.{d.get('adminFee','N/A')}")
    print(f"User Monthly ROI: {d.get('userMonthlyRoi','N/A')}")
    print(f"Annualized ROI: {d.get('annualizedRoi','N/A')}%")
    print(f"Total Trades: {d.get('totalTrades','N/A')}")
    
    strats = d.get('strategies', {})
    for name, s in strats.items():
        print(f"\n  {name}: {s.get('trades')} trades, WR={s.get('winRate')}, PnL=Rs.{s.get('totalNetPnl')}, Avg/Trade=Rs.{s.get('avgPerTrade')}")
except:
    # Show raw response
    print(f"Raw response ({len(r)} chars):")
    print(r[:2000])
    if len(r) > 2000: print(f"\n... truncated ({len(r)} total chars)")

s.close()

