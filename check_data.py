import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

print("=== Current candle data ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT COUNT(*), timeframe, MIN(timestamp), MAX(timestamp) FROM candle_data GROUP BY timeframe\\\"\" 2>&1"))

print("\n=== Symbols with data ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT COUNT(DISTINCT symbol) FROM candle_data\\\"\" 2>&1"))

print("\n=== Check Backfill API ===")
# The endpoint needs auth. Let's check
print(c("curl -s http://localhost:8080/api/admin/backfill/historical?months=6 -X POST 2>&1 | head -c 200"))

print("\n=== Try direct SQL backfill ===")
print("Checking Zerodha connectivity...")
print(c("docker logs stokr-lite-backend 2>&1 | grep -i 'zerodha\|kite\|backfill\|scheduler' | tail -5"))

s.close()

