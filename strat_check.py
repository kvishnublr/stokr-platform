import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# 1. Server health
print("=== Docker ===")
print(c("docker ps --format '{{.Names}} {{.Status}}' 2>&1 | head -5"))
print("\nHealth:", c("curl -s -m5 http://localhost:8080/actuator/health 2>/dev/null || echo 'down'"))

# 2. DB strategies
print("\n=== Strategies in DB ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, name, strategy_type, enabled FROM strategies ORDER BY id\\\"\" 2>&1"))

# 3. Data summary
print("\n=== Candle Data ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT timeframe, COUNT(*), COUNT(DISTINCT symbol), MIN(timestamp)::date, MAX(timestamp)::date FROM candle_data GROUP BY timeframe\\\"\" 2>&1"))

# 4. Model APIs (fast, no data needed)
print("\n=== Portfolio Model ===")
m = c("curl -s -m5 http://localhost:8080/api/backtest/portfolio/model 2>/dev/null | head -c 1200")
print(m[:1200])

print("\n=== BTST Model ===")
m = c("curl -s -m5 'http://localhost:8080/api/backtest/quickflip/pattern?months=3' 2>/dev/null | head -c 300")
print(m[:300])

# 5. Check recent signals (if any)  
print("\n=== Recent Signals ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT symbol, strategy_type, entry_price, exit_price, pnl, created_at FROM signals ORDER BY created_at DESC LIMIT 5\\\"\" 2>&1 | head -10"))
s.close()
