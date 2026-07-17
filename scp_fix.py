import paramiko,time,json
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Fix the file directly on server
fixes = [
    ('import java.time.LocalTime;', 'import java.time.LocalDateTime;\nimport java.time.LocalTime;'),
    ('static class Candle5m {\n        LocalTime timestamp;', 'static class Candle5m {\n        LocalDateTime timestamp;'),
    ('latest.timestamp != null ? latest.timestamp : LocalTime.now();', 'latest.timestamp != null ? latest.timestamp.toLocalTime() : LocalTime.now();'),
]

file_path = "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/MomentumSurgeStrategy.java"
sftp = s.open_sftp()
with sftp.file(file_path, 'r') as f:
    content = f.read().decode('utf-8')
for old, new in fixes:
    if old in content:
        content = content.replace(old, new)
        print(f"Fixed: {old[:50]}...")
    else:
        print(f"WARN: not found: {old[:50]}...")
with sftp.file(file_path, 'w') as f:
    f.write(content)
sftp.close()
print("File updated on server")

# Build and start
print("\nBuilding Docker...")
stdin,stdout,stderr = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose build backend 2>&1 | tail -8")
time.sleep(120)
out = stdout.read().decode('utf-8',errors='replace')
err = stderr.read().decode('utf-8',errors='replace')
print(out[-400:])
if 'failed' in out or 'failed' in err:
    print(f"BUILD FAILED - checking maven directly...")
    print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep ERROR | head -5"))
    s.close(); exit()

print("\nStarting...")
c("docker rm -f stokr-lite-backend 2>/dev/null; cd /root/stokr-platform/stokr-lite && docker compose up -d backend 2>&1 | tail -3")
time.sleep(35)

for i in range(12):
    h = c("curl -s http://localhost:8080/actuator/health")
    if h and 'UP' in h: print(f"Ready: {h}"); break
    time.sleep(3)

print("\n=== Portfolio Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -X POST 'http://localhost:8080/api/backtest/portfolio/run?months=3'")
time.sleep(120)
r = stdout.read().decode('utf-8',errors='replace')
if r.strip() and '{' in r:
    d = json.loads(r)
    print(f"Total: {d.get('totalTrades')}t, Net=Rs.{d.get('totalNetPnl')}, Monthly=Rs.{d.get('monthlyAvgPnl')}")
    print(f"User: Rs.{d.get('userProfit')} | Admin: Rs.{d.get('adminFee')} | ROI: {d.get('userMonthlyRoi')}")
    for n,st in d.get('strategies',{}).items():
        if isinstance(st,dict): print(f"  {n}: {st.get('trades')}t, WR={st.get('winRate')}, PnL=Rs.{st.get('totalNetPnl')}")
s.close()
