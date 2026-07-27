import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

# Apply sed fixes directly
file = "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/MomentumSurgeStrategy.java"

# Fix1: import LocalDateTime
c(f"sed -i 's/^import java.time.LocalTime;$/import java.time.LocalDateTime;\\nimport java.time.LocalTime;/' {file}")

# Fix2: Candle5m timestamp type
c(f"sed -i 's/LocalTime timestamp;/LocalDateTime timestamp;/' {file}")

# Fix3: .timestamp reference
c(f"sed -i 's/latest.timestamp != null ? latest.timestamp : LocalTime.now()/latest.timestamp != null ? latest.timestamp.toLocalTime() : LocalTime.now()/' {file}")

print("Fixes applied")

# Clean + build
print("Building...")
out = c(f"cd /root/stokr-platform/stokr-lite/backend && mvn clean package -DskipTests -q 2>&1 | tail -5")
print(out)

jar = c("ls -la /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar 2>/dev/null")
if 'stokr-lite' in jar:
    print("JAR built!")
    c("fuser -k 8080/tcp 2>/dev/null; sleep 2")
    c("nohup java -jar /root/stokr-platform/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar > /var/log/stokr.log 2>&1 &")
    time.sleep(40)
    print("Health:", c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null || echo 'starting...'"))

    # Run backtest
    print("\n=== Portfolio Backtest ===")
    stdin,stdout,stderr = s.exec_command("curl -s -m300 -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3' 2>&1")
    time.sleep(180)
    r = stdout.read().decode(errors='replace')
    import json
    if r.strip() and '{' in r:
        d = json.loads(r)
        print(f"Total Trades: {d.get('totalTrades')}")
        print(f"Total Net PnL: Rs.{d.get('totalNetPnl')}")
        print(f"Monthly Avg: Rs.{d.get('monthlyAvgPnl')}")
        print(f"User(75%): Rs.{d.get('userProfit')} | Admin(25%): Rs.{d.get('adminFee')}")
        print(f"User ROI: {d.get('userMonthlyRoi')}")
        for n,st in sorted(d.get('strategies',{}).items()):
            if isinstance(st,dict):
                t = st.get('trades',0)
                wr = st.get('winRate','N/A')
                pnl = st.get('totalNetPnl','N/A')
                print(f"  {n}: {t}t WR={wr} PnL=Rs.{pnl}")
    else:
        print(f"No result (len={len(r)})\n{r[:300]}")
else:
    print("BUILD FAILED")
    print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep 'ERROR' | head -5"))
s.close()

