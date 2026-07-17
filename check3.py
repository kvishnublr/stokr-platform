import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    print(r.stdout if r.stdout else r.stderr)

print("=== Check if cron ran today (system cron log) ===")
remote("grep zerodha /var/log/syslog 2>/dev/null | tail -5 || journalctl -u cron --since today 2>/dev/null | tail -10 || echo 'no syslog access'")

print("\n=== Check current token in DB ===")
remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, broker_name, client_id, access_token IS NOT NULL as has_token, auto_reconnect, status, updated_at FROM broker_accounts WHERE broker_name='ZERODHA';\"")

print("\n=== Try running token refresh manually now ===")
remote("cd /usr/local/bin && timeout 60 python3 zerodha_token_refresh.py 2>&1 | head -30")

print("\n=== Check tick_anomalies latest data ===")
remote("PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"SELECT id, symbol, magnitude, vwap_deviation, created_at FROM tick_anomalies ORDER BY created_at DESC LIMIT 5;\"")
