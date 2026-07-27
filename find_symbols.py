#!/usr/bin/env python3
import requests, json, psycopg2

conn = psycopg2.connect("host=localhost dbname=stokr_lite user=postgres password=`$POSTGRES_PASSWORD")
cur = conn.cursor()
cur.execute("SELECT access_token FROM broker_accounts WHERE id = 4")
TOKEN = cur.fetchone()[0]
cur.close()
conn.close()

HEADERS = {"Authorization": "token `$ZERODHA_API_KEY:" + TOKEN, "X-Kite-Version": "3"}

# Download instruments CSV to find correct NIFTY option symbols
resp = requests.get("https://api.kite.trade/instruments", headers=HEADERS, timeout=30)
print(f"Instruments response: {resp.status_code}, content-length: {len(resp.content)}")

# Parse CSV
lines = resp.text.split("\n")
header = lines[0]
print(f"Header: {header}")

# Find NIFTY options expiring Jul 22
nifty_options = []
for line in lines[1:]:
    parts = line.split(",")
    if len(parts) < 8:
        continue
    tradingsymbol = parts[2] if len(parts) > 2 else ""
    exchange = parts[1] if len(parts) > 1 else ""
    if "NIFTY" in tradingsymbol and "22JUL" in tradingsymbol.upper() and exchange == "NFO":
        nifty_options.append(line)

print(f"\nFound {len(nifty_options)} NIFTY Jul 22 options")
for line in nifty_options[:10]:
    parts = line.split(",")
    print(f"  token={parts[0]} exchange={parts[1]} symbol={parts[2]} expiry={parts[3]} strike={parts[4]} type={parts[5]}")

# Also find NIFTY futures
nifty_futs = []
for line in lines[1:]:
    parts = line.split(",")
    if len(parts) < 8:
        continue
    tradingsymbol = parts[2] if len(parts) > 2 else ""
    exchange = parts[1] if len(parts) > 1 else ""
    if "NIFTY" in tradingsymbol and "FUT" in tradingsymbol.upper() and exchange == "NFO" and "BANK" not in tradingsymbol and "MID" not in tradingsymbol and "FIN" not in tradingsymbol:
        nifty_futs.append(line)

print(f"\nFound {len(nifty_futs)} NIFTY futures")
for line in nifty_futs[:5]:
    parts = line.split(",")
    print(f"  token={parts[0]} exchange={parts[1]} symbol={parts[2]} expiry={parts[3]}")

