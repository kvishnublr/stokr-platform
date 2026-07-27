import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("=== Docker status ===")
print(c("docker ps -a --format '{{.Names}} {{.Status}}' 2>&1 | head -10"))

print("\n=== Restarting ===")
c("cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1 | tail -5")
time.sleep(35)

print("\n=== Containers ===")
print(c("docker ps --format '{{.Names}} {{.Status}}' | grep stokr"))

print("\n=== Health ===")
print(c("curl -s -m5 http://localhost:8080/actuator/health 2>/dev/null || echo 'still down'"))

# Quick strategy check
print("\n=== Strategies ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, name, strategy_type, enabled FROM strategies ORDER BY id\\\"\" 2>&1"))

# Run BTST backtest
print("\n=== BTST Backtest ===")
stdin,stdout,stderr = s.exec_command("curl -s -m120 -X POST 'http://localhost:8080/api/backtest/quickflip/pattern?months=3' 2>/dev/null")
time.sleep(90)
r = stdout.read().decode(errors='replace')
import json
try:
    d = json.loads(r)
    print(f"Trades: {d.get('totalTrades')} | WR: {d.get('winRate')} | Net: Rs.{d.get('totalNetPnl')}")
except: print(f"Raw: {r[:300]}")
s.close()

