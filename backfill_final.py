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

r2 = subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-t", "-A", "-c",
    "SELECT tradingsymbol, instrument_token FROM zerodha_instruments WHERE exchange='NSE'"],
    capture_output=True, text=True, env=env, timeout=15)
instruments = {}
for line in r2.stdout.strip().split("\n"):
    if "|" in line:
        sym, tok = line.split("|", 1)
        instruments[sym.strip()] = tok.strip()
print(f"Instruments: {len(instruments)}")

symbols_rows = psql("SELECT symbol FROM universe_group_members WHERE universe_group_id = (SELECT id FROM universe_groups WHERE group_key='NIFTY_100')")
symbols = [s.strip() for s in symbols_rows.split("\n") if s.strip()]
print(f"NIFTY_100: {len(symbols)}")

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
            sql = f"INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume) VALUES ('{symbol}', 'daily', '{ts}', {o}, {h}, {l}, {close}, {vol}) ON CONFLICT DO NOTHING;"
            subprocess.run(["psql", "-h", "localhost", "-U", "postgres", "-d", "stokr_lite", "-c", sql],
                capture_output=True, text=True, env=env, timeout=5)
            total += 1
        time.sleep(0.05)
        if (i + 1) % 20 == 0:
            print(f"  {i+1}/{len(symbols)} done, {total} candles")
    except Exception as e:
        errs += 1
        if errs <= 5:
            print(f"  Error {symbol}: {e}")

print(f"Done: {total} candles, {errs} errors")
