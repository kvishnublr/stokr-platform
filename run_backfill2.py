import subprocess, json, time

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=600)
    return r.stdout + r.stderr

# Write a proper backfill script on server
script = r"""import json, subprocess, time
import urllib.request

# Get access token
r = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    "SELECT access_token FROM broker_accounts WHERE id=4"], capture_output=True, text=True)
token = r.stdout.strip()
print(f"Token: {token[:15]}...")

# Get instrument tokens from DB
r2 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    "SELECT tradingsymbol, instrument_token FROM zerodha_instruments WHERE exchange='NSE'"], capture_output=True, text=True)
instruments = {}
for line in r2.stdout.strip().split('\n'):
    if '|' in line:
        sym, tok = line.split('|', 1)
        instruments[sym.strip()] = tok.strip()
print(f"Instruments: {len(instruments)}")

# Get NIFTY_100 symbols
r3 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    """SELECT symbol FROM universe_group_members WHERE universe_group_id = (SELECT id FROM universe_groups WHERE group_key='NIFTY_100')"""], capture_output=True, text=True)
symbols = [s.strip() for s in r3.stdout.strip().split('\n') if s.strip()]
print(f"NIFTY_100 symbols: {len(symbols)}")

base_url = "https://api.kite.trade/instruments/historical"
headers = {"Authorization": f"Token {token}", "X-Kite-Version": "3"}
from_date = "2026-07-09"
to_date = "2026-07-13"

total = 0
errs = 0

for i, symbol in enumerate(symbols):
    inst_token = instruments.get(symbol)
    if not inst_token:
        continue
    
    try:
        url = f"{base_url}/{inst_token}/daily?from={from_date}&to={to_date}"
        req = urllib.request.Request(url, headers=headers)
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read())
        candles = data.get("data", {}).get("candles", [])
        
        for c in candles:
            ts = c[0]
            o, h, l, close, vol = c[1], c[2], c[3], c[4], c[5]
            sql = f"""INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume)
                VALUES ('{symbol}', 'daily', '{ts}', {o}, {h}, {l}, {close}, {vol})
                ON CONFLICT DO NOTHING;"""
            r4 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-c", sql],
                capture_output=True, text=True, timeout=5)
            total += 1
        
        time.sleep(0.05)
        if (i + 1) % 20 == 0:
            print(f"  Processed {i+1}/{len(symbols)} symbols, {total} candles inserted")
    except Exception as e:
        errs += 1
        if errs <= 5:
            print(f"  Error {symbol}: {e}")

print(f"\nDone: {total} candles inserted, {errs} errors")
"""

with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\backfill_zerodha.py", "w") as f:
    f.write(script)

import os
os.system('scp -o StrictHostKeyChecking=no "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\backfill_zerodha.py" root@173.249.55.84:/tmp/backfill_zerodha.py')
print("Running backfill...")
result = remote("python3 /tmp/backfill_zerodha.py 2>&1")
print(result[-1000:])
