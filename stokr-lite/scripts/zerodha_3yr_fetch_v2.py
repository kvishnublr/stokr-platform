#!/usr/bin/env python3
"""Fetch 3yr daily data using Zerodha API with token from DB"""
import subprocess
import urllib.request
import json
import time

# Get token from DB
result = subprocess.run(
    ['bash', '-c', 'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT access_token FROM broker_accounts WHERE id=1;"'],
    capture_output=True, text=True
)
ACCESS_TOKEN = result.stdout.strip()
print(f"Token from DB: {ACCESS_TOKEN[:12]}..." if ACCESS_TOKEN else "NO TOKEN FOUND")

API_KEY = "`$ZERODHA_API_KEY"

# Test: profile
print("\n=== Testing Zerodha API ===")
try:
    req = urllib.request.Request("https://api.kite.trade/clients/user/profile")
    req.add_header("X-Kite-Version", "3")
    req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
        print(f"Profile: {data.get('data', {}).get('user_name', 'N/A')} - OK")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"Profile FAILED: {e.code} - {body[:200]}")
    exit(1)

# Get NIFTY_50 symbols from DB
result = subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT symbol FROM universe_symbols WHERE group_id IN (SELECT id FROM universe_groups WHERE name = 'NIFTY_50') ORDER BY symbol;\""],
    capture_output=True, text=True
)
symbols_from_db = [s.strip() for s in result.stdout.strip().split('\n') if s.strip()]
print(f"\nNIFTY_50 symbols from DB: {len(symbols_from_db)}")
print(f"First 5: {symbols_from_db[:5]}")

# Get instrument tokens for these symbols
result = subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily' ORDER BY symbol;\""],
    capture_output=True, text=True
)
symbols_in_daily = [s.strip() for s in result.stdout.strip().split('\n') if s.strip()]
print(f"Symbols in daily candle_data: {len(symbols_in_daily)}")

# Fetch instruments from Zerodha to get trading_token
print("\n=== Fetching instruments for token mapping ===")
req = urllib.request.Request("https://api.kite.trade/instruments")
req.add_header("X-Kite-Version", "3")
req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
with urllib.request.urlopen(req, timeout=60) as resp:
    text = resp.read().decode()

lines = text.strip().split('\n')
header = lines[0].split(',')
# Find indices for key columns
sym_idx = header.index('tradingsymbol')
token_idx = header.index('instrument_token')
exchange_idx = header.index('exchange')
type_idx = header.index('instrument_type')

nse_map = {}
for line in lines[1:]:
    cols = line.split(',')
    if len(cols) > max(sym_idx, exchange_idx, type_idx, token_idx):
        if cols[exchange_idx] == 'NSE' and cols[type_idx] == 'EQ':
            sym = cols[sym_idx]
            token = cols[token_idx]
            nse_map[sym] = token

print(f"NSE EQ instruments found: {len(nse_map)}")

# Match with our targets
all_targets = list(set(symbols_from_db + symbols_in_daily))
matched = {s: nse_map[s] for s in all_targets if s in nse_map}
print(f"Matched targets: {len(matched)} of {len(all_targets)}")

# Fetch 3yr data
from datetime import datetime, timedelta
end_date = datetime.now().strftime("%Y-%m-%d")
start_date = (datetime.now() - timedelta(days=3*365)).strftime("%Y-%m-%d")
print(f"\nFetching daily data: {start_date} to {end_date}")

total = 0
errors = 0
csv_lines = []
items = list(matched.items())

# First delete existing daily data
subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"DELETE FROM candle_data WHERE timeframe = 'daily';\""],
    capture_output=True, text=True
)
print("Existing daily data deleted")

for i, (sym, token) in enumerate(items):
    print(f"[{i+1}/{len(items)}] {sym} (token={token})...", end=" ", flush=True)
    try:
        url = f"https://api.kite.trade/instruments/historical/{token}/day?from={start_date}&to={end_date}"
        req = urllib.request.Request(url)
        req.add_header("X-Kite-Version", "3")
        req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read())
            candles = data.get("data", {}).get("candles", [])
            for c in candles:
                ts = str(c[0]).replace("T", " ")[:19]
                csv_lines.append(f"{sym}|daily|{ts}|{c[1]}|{c[2]}|{c[3]}|{c[4]}|{int(c[5])}")
            total += len(candles)
            print(f"{len(candles)} candles")
    except Exception as e:
        errors += 1
        print(f"ERROR: {e}")
    time.sleep(0.3)

print(f"\nTotal: {total} candles, {errors} errors")

# Write CSV and load
csv_file = "/tmp/daily_candles_3yr_zerodha.csv"
with open(csv_file, "w") as f:
    f.write("\n".join(csv_lines))
print(f"CSV: {len(csv_lines)} lines written")

if csv_lines:
    load_sql = f"""COPY candle_data(symbol, timeframe, timestamp, open, high, low, close, volume) FROM '{csv_file}' WITH DELIMITER '|';"""
    result = subprocess.run(
        ['bash', '-c', f'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "{load_sql}"'],
        capture_output=True, text=True
    )
    print(f"Load: {result.stdout.strip()}")
    if result.stderr:
        print(f"Load error: {result.stderr.strip()[:200]}")

# Verify
result = subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT MIN(timestamp)::date, MAX(timestamp)::date, COUNT(DISTINCT symbol), COUNT(*) FROM candle_data WHERE timeframe='daily';\""],
    capture_output=True, text=True
)
print(f"\nFinal DB state: {result.stdout.strip()}")

