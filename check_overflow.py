import subprocess

def query(q):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84",
        "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c " + repr(q)
    ], capture_output=True, text=True, timeout=20)
    print(r.stdout if r.stdout else r.stderr)

print("=== CHARTINK CANDLE INSERT ISSUE - check candle_data precision ===")
query("SELECT column_name, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='candle_data' AND numeric_precision IS NOT NULL;")

print("\n=== Check for large values in candle_data ===")
query("SELECT symbol, MAX(close), MAX(volume) FROM candle_data WHERE timeframe='1min' AND date_trunc('day', timestamp) = CURRENT_DATE GROUP BY symbol HAVING MAX(close) > 99999 OR MAX(volume) > 9999999 ORDER BY MAX(close) DESC LIMIT 10;")

print("\n=== CRONTAB CHECK ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "crontab -l 2>&1"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else r.stderr)

print("\n=== TOKEN REFRESH LOG (last entry) ===")
r = subprocess.run([
    "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
    "root@173.249.55.84",
    "tail -20 /var/log/stokr-token-refresh.log 2>&1"
], capture_output=True, text=True, timeout=20)
print(r.stdout if r.stdout else r.stderr)

