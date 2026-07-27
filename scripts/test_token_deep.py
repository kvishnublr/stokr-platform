#!/usr/bin/env python3
"""Fresh token test - get a new token and test immediately"""
import requests, subprocess, os, hashlib, time

API_KEY = os.environ.get("ZERODHA_API_KEY", "")
API_SECRET = os.environ.get("ZERODHA_API_SECRET", "")
CLIENT_ID = "DS8838"

env = os.environ.copy()
env['PGPASSWORD'] = os.environ.get("POSTGRES_PASSWORD", "")

# Get token from DB
result = subprocess.run(
    ["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite",
     "-t", "-A", "-c", "SELECT access_token FROM broker_accounts WHERE id=4;"],
    capture_output=True, text=True, env=env
)
db_token = result.stdout.strip()

print(f"DB token: {db_token[:12]}... len={len(db_token)}")
print(f"DB token repr: {repr(db_token)}")

# Try with the DB token
headers = {"Authorization": f"token {API_KEY}:{db_token}", "X-Kite-Version": "3"}
r = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print(f"\nDB token test: {r.status_code} {r.text[:200]}")

# Check if there's trailing whitespace
if db_token != db_token.strip():
    print("WARNING: Token has whitespace!")
    headers2 = {"Authorization": f"token {API_KEY}:{db_token.strip()}", "X-Kite-Version": "3"}
    r2 = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers2)
    print(f"Stripped token test: {r2.status_code} {r2.text[:200]}")

# Test with orders endpoint (different from quote)
r3 = requests.get("https://api.kite.trade/orders", headers=headers)
print(f"\nOrders test: {r3.status_code} {r3.text[:200]}")
