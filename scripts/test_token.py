#!/usr/bin/env python3
"""Test access token from DB"""
import requests
import subprocess
import os

API_KEY = "zazlrld244cc6jf0"

env = os.environ.copy()
env['PGPASSWORD'] = 'stokr2026'
result = subprocess.run(
    ["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite",
     "-t", "-A", "-c", "SELECT access_token FROM broker_accounts WHERE id=4;"],
    capture_output=True, text=True, env=env
)
token = result.stdout.strip()
print(f"Token length: {len(token)}")
print(f"Token preview: {token[:8]}...{token[-4:]}")

# Test with Kite API
headers = {"Authorization": f"token {API_KEY}:{token}"}
r = requests.get("https://api.kite.trade/orders", headers=headers)
print(f"Orders API: {r.status_code} - {r.text[:200]}")

# Test NIFTY quote
r2 = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print(f"NIFTY Quote: {r2.status_code} - {r2.text[:300]}")
