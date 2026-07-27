import paramiko,time,json,sys
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=10)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Quick status
h = c("docker ps --format '{{.Names}} {{.Status}}' 2>&1")
if not h or 'stokr-lite-backend' not in h:
    print("Docker down, restarting...")
    c("systemctl start docker 2>/dev/null; sleep 2")
    c("docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null")
    out = c("cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1 | tail -5")
    print(out)
    time.sleep(35)

h = c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null")
if 'UP' not in str(h):
    print(f"Waiting for backend... {h}")
    for i in range(20):
        time.sleep(3)
        h = c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null")
        if 'UP' in str(h): break
    print(f"Health: {h}")
else:
    print(f"Health: {h}")

# Run backtest
print("\n=== Running Portfolio Backtest (3 months) ===")
sys.stdout.flush()
stdin,stdout,stderr = s.exec_command("curl -s -m300 -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
time.sleep(180)
r = stdout.read().decode(errors='replace')

if r.strip() and '{' in r:
    d = json.loads(r)
    sys.stdout.write(f"Total Trades: {d.get('totalTrades')}\n")
    sys.stdout.write(f"Total Net PnL: Rs.{d.get('totalNetPnl')}\n")
    sys.stdout.write(f"Monthly Avg: Rs.{d.get('monthlyAvgPnl')}\n")
    sys.stdout.write(f"Annualized ROI: {d.get('annualizedRoi')}%\n")
    sys.stdout.write(f"User Profit (75%): Rs.{d.get('userProfit')}\n")
    sys.stdout.write(f"Admin Fee (25%): Rs.{d.get('adminFee')}\n")
    sys.stdout.write(f"User Monthly ROI: {d.get('userMonthlyRoi')}\n")
    sys.stdout.write(f"\n--- Per Strategy ---\n")
    for n,st in sorted(d.get('strategies',{}).items()):
        if isinstance(st,dict):
            t = st.get('trades',0)
            wr = st.get('winRate','N/A')
            pnl = st.get('totalNetPnl','N/A')
            avg = st.get('avgPerTrade','N/A')
            dd = st.get('maxDrawdown','N/A')
            sys.stdout.write(f"\n  {n}:\n")
            sys.stdout.write(f"    Trades: {t}\n")
            sys.stdout.write(f"    Win Rate: {wr}\n")
            sys.stdout.write(f"    Total PnL: Rs.{pnl}\n")
            sys.stdout.write(f"    Avg/Trade: Rs.{avg}\n")
            sys.stdout.write(f"    Max Drawdown: Rs.{dd}\n")
    sys.stdout.flush()
else:
    print(f"Empty/bad response (len={len(r)})\n{r[:500]}")
    print("\nChecking logs...")
    print(c("docker logs stokr-lite-backend --tail 10 2>&1"))

s.close()

