#!/usr/bin/env python3
import requests, subprocess, os

env = os.environ.copy()
env['PGPASSWORD'] = 'stokr2026'
result = subprocess.run(
    ["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite",
     "-t", "-A", "-c", "SELECT access_token FROM broker_accounts WHERE id=4;"],
    capture_output=True, text=True, env=env
)
token = result.stdout.strip()
API_KEY = "zazlrld244cc6jf0"
headers = {"Authorization": f"token {API_KEY}:{token}", "X-Kite-Version": "3"}

# Test NFO futures quote
r = requests.get("https://api.kite.trade/quote/?i=NFO:NIFTY26JULFUT", headers=headers)
print("NFO:NIFTY26JULFUT response:")
print(r.status_code, r.text[:500])

# Test NIFTY spot
r2 = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print("\nNSE:NIFTY 50 response:")
print(r2.status_code, r2.text[:500])
