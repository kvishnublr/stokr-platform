import paramiko,time,json,sys
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Upload JAR
print("Uploading JAR...")
sftp = s.open_sftp()
local_jar = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\target\stokr-lite-1.0.0-SNAPSHOT.jar"
remote_jar = "/root/stokr-lite.jar"
sftp.put(local_jar, remote_jar)
sftp.close()
print("JAR uploaded")

# Seed V35
c("su - postgres -c \"psql -d stokr_lite -c \\\"INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES ('Institutional Footprint', 'VSA Smart Money Engine', 'INSTITUTIONAL_FOOTPRINT', 'EQUITY', '{}', true, now(), now()) ON CONFLICT (name) DO UPDATE SET enabled=true\\\"\" 2>&1")

# Kill old, start new
c("fuser -k 8080/tcp 2>/dev/null; docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null; sleep 2")
print("Starting JAR...")
c("nohup java -jar /root/stokr-lite.jar > /var/log/stokr.log 2>&1 &")

# Wait for startup
time.sleep(45)
for i in range(15):
    h = c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null")
    if 'UP' in str(h):
        print(f"Ready: {h}")
        break
    time.sleep(3)

if not ('UP' in str(h or '')):
    print("Not ready. Logs:")
    print(c("tail -20 /var/log/stokr.log 2>&1 | grep -E 'Started|ERROR|Exception'"))
    s.close()
    exit()

# Run backtest
print("\n=== Running Portfolio Backtest ===")
sys.stdout.flush()
stdin,stdout,stderr = s.exec_command("curl -s -m300 -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
time.sleep(180)
r = stdout.read().decode(errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    sys.stdout.write(f"\nTotal Trades: {d.get('totalTrades')}\n")
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
            sys.stdout.write(f"  {n}: {t}t WR={wr} PnL=Rs.{pnl} Avg=Rs.{avg} DD=Rs.{dd}\n")
    sys.stdout.flush()
else:
    print(f"No response (len={len(r)}): {r[:300]}")
    print("Logs:", c("tail -5 /var/log/stokr.log 2>&1"))
s.close()

