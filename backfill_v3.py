import json, subprocess, time, os, csv, io
import urllib.request
import gzip

env = os.environ.copy()
env["PGPASSWORD"] = "`$POSTGRES_PASSWORD"

def psql(sql):
    r = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c", sql],
        capture_output=True, text=True, env=env, timeout=10)
    return r.stdout.strip()

token = psql("SELECT access_token FROM broker_accounts WHERE id=4")
print(f"Token: {token[:15]}...")

# Get NIFTY_100 symbols
r2 = psql("SELECT symbol FROM universe_symbols WHERE group_id = (SELECT id FROM universe_groups WHERE group_key='NIFTY_100') AND enabled=true")
symbols = set(s.strip() for s in r2.split("\n") if s.strip())
print(f"NIFTY_100 symbols: {len(symbols)}")

# Step 1: Download instrument master from Kite
print("Downloading instrument master...")
req = urllib.request.Request("https://api.kite.trade/instruments", headers={"Authorization": f"Token {token}", "X-Kite-Version": "3"})
resp = urllib.request.urlopen(req, timeout=30)
raw = resp.read()

# Check if gzipped
if raw[:2] == b'\x1f\x8b':
    raw = gzip.decompress(raw)

text = raw.decode('utf-8')
reader = csv.DictReader(io.StringIO(text))

# Map NSE EQ symbols to instrument tokens
symbol_to_token = {}
for row in reader:
    if row.get('exchange') == 'NSE' and row.get('instrument_type') == 'EQ':
        ts = row.get('tradingsymbol', '')
        # Remove EQ suffix for matching
        base = ts.replace(' EQ', '').replace('-EQ', '')
        if base in symbols or ts in symbols:
            symbol_to_token[base if base in symbols else ts] = row['instrument_token']

print(f"Mapped {len(symbol_to_token)} NIFTY_100 symbols to instrument tokens")

# Step 2: Fetch daily candles for Jul 9-13
base_url = "https://api.kite.trade/instruments/historical"
headers = {"Authorization": f"Token {token}", "X-Kite-Version": "3"}
from_date = "2026-07-09"
to_date = "2026-07-13"
total = 0
errs = 0
days_found = set()

for i, (symbol, inst_token) in enumerate(symbol_to_token.items()):
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
            print(f"  {i+1}/{len(symbol_to_token)} done, {total} candles, days: {sorted(days_found)}")
    except urllib.error.HTTPError as e:
        errs += 1
        if errs <= 5:
            print(f"  HTTP {e.code} {symbol}")
    except Exception as e:
        errs += 1
        if errs <= 5:
            print(f"  Error {symbol}: {e}")

print(f"\nDone: {total} candles inserted, {errs} errors, days: {sorted(days_found)}")

# Step 3: Verify
count = psql("SELECT count(*) FROM candle_data WHERE timeframe='daily' AND timestamp >= '2026-07-09'")
print(f"Daily candles from Jul 9+: {count}")

