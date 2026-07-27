import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Check which DB the app actually uses for auth - maybe stokr_lite has a users table
print("=== stokr_lite tables ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE '%user%' OR tablename LIKE '%auth%'\\\"\" 2>&1"))

# Check CandleData entity to see what timeframe options exist
print("\n=== CandleData table ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT symbol, timeframe, COUNT(*), MAX(timestamp) FROM candle_data WHERE timeframe='daily' GROUP BY symbol, timeframe ORDER BY COUNT(*) DESC LIMIT 3\\\"\" 2>&1"))

print("\n=== Total daily candles ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT COUNT(*), COUNT(DISTINCT symbol), MIN(timestamp), MAX(timestamp) FROM candle_data WHERE timeframe='daily'\\\"\" 2>&1"))

# Check broker_accounts for access token
print("\n=== Zerodha active account ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, broker_name, status, access_token IS NOT NULL as has_token, token_expiry FROM broker_accounts WHERE broker_name='ZERODHA' AND status='ACTIVE'\\\"\" 2>&1"))

# The APPROACH: direct Zerodha Kite Historical API via Python
# Check if kiteconnect is available
print("\n=== Python kiteconnect ===")
print(c("pip3 list 2>/dev/null | grep -i kite"))

s.close()

