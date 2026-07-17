import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("Health:", c("curl -s -m3 http://localhost:8080/actuator/health"))

# Also check DB
print("\nStrategies:")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, strategy_type, enabled FROM strategies WHERE strategy_type IN ('INSTITUTIONAL_FOOTPRINT','BTST','3_DAY_MOMENTUM_SWING','20_DAY_BREAKOUT') ORDER BY id\\\"\" 2>&1"))

print("\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -m180 -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(150)
r = stdout.read().decode(errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    print(f"Total: {d.get('totalTrades')}t | Net=Rs.{d.get('totalNetPnl')} | Monthly=Rs.{d.get('monthlyAvgPnl')}")
    print(f"User=Rs.{d.get('userProfit')} | Admin=Rs.{d.get('adminFee')} | ROI={d.get('userMonthlyRoi')}")
    for n,st in sorted(d.get('strategies',{}).items()):
        if isinstance(st,dict) and st.get('trades',0) > 0:
            print(f"  {n}: {st['trades']}t WR={st.get('winRate')} PnL=Rs.{st.get('totalNetPnl')} DD=Rs.{st.get('maxDrawdown')}")
else:
    print(f"No response ({len(r)}c): {r[:300]}")
s.close()
