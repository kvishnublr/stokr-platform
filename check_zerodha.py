import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Check Zerodha config
print("=== Broker account full details ===")
print(c("su - postgres -c \"psql -d stokr_lite -c \\\"SELECT id, broker_name, status, api_key, access_token IS NOT NULL as has_token, token_expiry FROM broker_accounts WHERE broker_name='ZERODHA'\\\"\" 2>&1"))

print("\n=== Check env vars in container ===")
print(c("docker exec stokr-lite-backend env | grep -i zerodha"))

print("\n=== Check application.properties/yml ===")
print(c("docker exec stokr-lite-backend cat /app/BOOT-INF/classes/application.properties 2>/dev/null | grep -i zerodha || docker exec stokr-lite-backend cat /app/BOOT-INF/classes/application.yml 2>/dev/null | grep -i zerodha"))

print("\n=== Check the HistoricalDataBackfillService for API key usage ===")
print(c("docker exec stokr-lite-backend jar tf /app/app.jar | grep -i backfill"))

s.close()

