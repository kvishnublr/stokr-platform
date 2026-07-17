import subprocess, json, time

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=600)
    return r.stdout + r.stderr

# Write a backfill script that uses the Zerodha API to get daily candles for Jul 9, 10, 11, 13
script = '''
import json, urllib.request, urllib.parse
from datetime import datetime, timedelta

# Get access token from DB
import subprocess
r = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    "SELECT access_token FROM broker_accounts WHERE id=4"], capture_output=True, text=True)
token = r.stdout.strip()

# Get NIFTY_100 symbols
r2 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    "SELECT symbol FROM universe_group_members WHERE universe_group_id = (SELECT id FROM universe_groups WHERE group_key=\\'NIFTY_100\\')"], capture_output=True, text=True)
symbols = [s.strip() for s in r2.stdout.strip().split("\\n") if s.strip()]
print(f"Symbols: {len(symbols)}")

# Zerodha historical API
base_url = "https://api.kite.trade/instruments/historical"
headers = {"Authorization": f"Token {token}", "X-Kite-Version": "3"}

# Dates to fetch: Jul 9, 10, 11, 13
from_date = "2026-07-09"
to_date = "2026-07-13"

total_inserted = 0
errors = 0

for symbol in symbols:
    try:
        # Get instrument token for this symbol from zerodha_instruments
        r3 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
            f"SELECT instrument_token FROM zerodha_instruments WHERE tradingsymbol=\\'{symbol}\\' AND exchange=\\'NSE\\' LIMIT 1"], capture_output=True, text=True)
        inst_token = r3.stdout.strip()
        if not inst_token:
            continue

        url = f"{base_url}/{inst_token}/daily?from={from_date}&to={to_date}"
        req = urllib.request.Request(url, headers=headers)
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read())
        candles = data.get("data", {}).get("candles", [])

        for c in candles:
            ts = c[0]  # "2026-07-09T00:00:00+05:30"
            o, h, l, close, vol = c[1], c[2], c[3], c[4], c[5]

            # Insert into candle_data
            insert_sql = f"""INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume)
                VALUES (\\'{symbol}\\', \\'daily\\', \\'{ts}\\', {o}, {h}, {l}, {close}, {vol})
                ON CONFLICT DO NOTHING;"""
            r4 = subprocess.run(["PGPASSWORD=stokr2026", "psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-c", insert_sql],
                capture_output=True, text=True, timeout=5)
            if r4.returncode == 0:
                total_inserted += 1
            else:
                errors += 1

        time.sleep(0.05)  # Rate limit
    except Exception as e:
        errors += 1
        if errors <= 3:
            print(f"Error {symbol}: {e}")

print(f"Done: {total_inserted} candles inserted, {errors} errors")
'''

with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\backfill_zerodha.py", "w") as f:
    f.write(script)

# Upload and run
import os
os.system('scp -o StrictHostKeyChecking=no "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\backfill_zerodha.py" root@173.249.55.84:/tmp/backfill_zerodha.py')
print("Uploaded. Running...")
result = remote("python3 /tmp/backfill_zerodha.py")
print(result[:2000])
