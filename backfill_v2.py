import json, subprocess, time, os
import urllib.request

env = os.environ.copy()
env["PGPASSWORD"] = "stokr2026"

def psql(sql):
    r = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c", sql],
        capture_output=True, text=True, env=env, timeout=10)
    return r.stdout.strip()

token = psql("SELECT access_token FROM broker_accounts WHERE id=4")
print(f"Token: {token[:15]}...")

# Get instrument tokens from universe_symbols
r2 = psql("SELECT symbol, instrument_token FROM universe_symbols WHERE group_id = (SELECT id FROM universe_groups WHERE group_key='NIFTY_100') AND enabled=true AND instrument_token IS NOT NULL")
instruments = {}
for line in r2.split("\n"):
    if "|" in line:
        sym, tok = line.split("|", 1)
        instruments[sym.strip()] = tok.strip()
print(f"NIFTY_100 instruments with tokens: {len(instruments)}")

if len(instruments) == 0:
    print("ERROR: No instrument tokens found!")
    r3 = psql("SELECT symbol, instrument_token FROM universe_symbols LIMIT 5")
    print(f"Sample: {r3}")
    exit(1)

base_url = "https://api.kite.trade/instruments/historical"
headers = {"Authorization": f"Token {token}", "X-Kite-Version": "3"}
from_date = "2026-07-09"
to_date = "2026-07-13"
total = 0
errs = 0
days_found = set()

for i, (symbol, inst_token) in enumerate(instruments.items()):
    try:
        url = f"{base_url}/{inst_token}/daily?from={from_date}&to={to_date}"
        req = urllib.request.Request(url, headers=headers)
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read())
        candles = data.get("data", {}).get("candles", [])
        
        for c in candles:
            ts = c[0]
            o, h, l, close, vol = c[1], c[2], c[3], c[4], c[5]
            day = ts[:10]
            days_found.add(day)
            sql = f"INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume) VALUES ('{symbol}', 'daily', '{ts}', {o}, {h}, {l}, {close}, {vol}) ON CONFLICT DO NOTHING;"
            subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-c", sql],
                capture_output=True, text=True, env=env, timeout=5)
            total += 1
        
        time.sleep(0.05)
        if (i + 1) % 20 == 0:
            print(f"  {i+1}/{len(instruments)} done, {total} candles, days: {sorted(days_found)}")
    except urllib.error.HTTPError as e:
        errs += 1
        if errs <= 5:
            print(f"  HTTP {e.code} {symbol}")
    except Exception as e:
        errs += 1
        if errs <= 5:
            print(f"  Error {symbol}: {e}")

print(f"\nDone: {total} candles inserted, {errs} errors, days: {sorted(days_found)}")
