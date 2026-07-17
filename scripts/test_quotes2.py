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
print(f"Token: '{token[:8]}...{token[-4:]}' len={len(token)}")
API_KEY = "zazlrld244cc6jf0"
auth = f"token {API_KEY}:{token}"
print(f"Auth header: {auth[:30]}...")

headers = {"Authorization": auth, "X-Kite-Version": "3"}

r = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print(f"\nStatus: {r.status_code}")
print(f"Response: {r.text[:300]}")
