import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

print("=== QuickFlip Detailed Backtest ===")
cmd = "curl -s -X POST 'http://localhost:8080/api/backtest/quickflip/run?months=3' 2>&1"
stdin,stdout,stderr = s.exec_command(cmd)
time.sleep(90)
r = stdout.read().decode('utf-8',errors='replace')

import json
try:
    d = json.loads(r)
    print(f"Total Trades: {d.get('totalTrades')}")
    print(f"Wins: {d.get('wins')}, Losses: {d.get('losses')}")
    print(f"Win Rate: {d.get('winRate')}")
    print(f"Total Net PnL: Rs.{d.get('totalNetPnl')}")
    print(f"Avg per Trade: Rs.{d.get('avgPerTrade')}")
    print(f"Max Drawdown: Rs.{d.get('maxDrawdown')}")
    print(f"Max Profit: Rs.{d.get('maxProfit')}, Max Loss: Rs.{d.get('maxLoss')}")
    
    # Exit breakdown
    print(f"\nExit Types: {d.get('exitTypeBreakdown',{})}")
    
    # Pattern breakdown
    pb = d.get('patternBreakdown', {})
    for pat, stats in pb.items():
        if isinstance(stats, dict):
            print(f"\n  Pattern: {pat}")
            for k,v in stats.items():
                print(f"    {k}: {v}")
except Exception as e:
    print(f"Parse error: {e}")
    print(f"Raw ({len(r)} chars): {r[:1500]}")

print("\n\n=== DB Summary ===")
print(c("su - postgres -c \"psql -d stokr_lite -c 'SELECT timeframe, COUNT(*), COUNT(DISTINCT symbol), MIN(timestamp), MAX(timestamp) FROM candle_data GROUP BY timeframe'\" 2>&1"))
s.close()
