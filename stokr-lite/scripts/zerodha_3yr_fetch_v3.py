#!/usr/bin/env python3
"""Fetch 3yr daily data using Zerodha API - skip profile, go straight to data"""
import subprocess
import urllib.request
import json
import time
from datetime import datetime, timedelta

# Get token from DB
result = subprocess.run(
    ['bash', '-c', 'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT access_token FROM broker_accounts WHERE id=1;"'],
    capture_output=True, text=True
)
ACCESS_TOKEN = result.stdout.strip()
API_KEY = "`$ZERODHA_API_KEY"
print(f"Token: {ACCESS_TOKEN[:12]}...")

# Quick test: fetch 1 candle for RELIANCE
print("\n=== Quick test ===")
try:
    url = "https://api.kite.trade/instruments/historical/738561/day?from=2026-07-07&to=2026-07-08"
    req = urllib.request.Request(url)
    req.add_header("X-Kite-Version", "3")
    req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
        candles = data.get("data", {}).get("candles", [])
        print(f"RELIANCE: {len(candles)} candles - {candles[0] if candles else 'none'}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"FAILED: {e.code} - {body[:200]}")
    exit(1)

# Fetch instruments to build symbol->token map
print("\n=== Fetching instruments ===")
req = urllib.request.Request("https://api.kite.trade/instruments")
req.add_header("X-Kite-Version", "3")
req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
with urllib.request.urlopen(req, timeout=60) as resp:
    text = resp.read().decode()

lines = text.strip().split('\n')
header = lines[0].split(',')
sym_idx = header.index('tradingsymbol')
token_idx = header.index('instrument_token')
exchange_idx = header.index('exchange')
type_idx = header.index('instrument_type')
lot_idx = header.index('lot_size') if 'lot_size' in header else -1

nse_map = {}
for line in lines[1:]:
    cols = line.split(',')
    if len(cols) > max(sym_idx, exchange_idx, type_idx, token_idx):
        if cols[exchange_idx] == 'NSE' and cols[type_idx] == 'EQ':
            nse_map[cols[sym_idx]] = cols[token_idx]
print(f"NSE EQ: {len(nse_map)}")

# Get all symbols we want (NIFTY_50 + extras)
NIFTY_50 = [
    "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK",
    "SBIN", "BHARTIARTL", "ITC", "KOTAKBANK", "LT",
    "HINDUNILVR", "AXISBANK", "MARUTI", "BAJFINANCE", "ASIANPAINT",
    "SUNPHARMA", "TITAN", "ULTRACEMCO", "WIPRO", "HCLTECH",
    "TATAMOTORS", "ONGC", "NTPC", "POWERGRID", "ADANIPORTS",
    "JSWSTEEL", "TATASTEEL", "COALINDIA", "M&M", "TECHM",
    "ADANIENT", "GRASIM", "BAJAJFINSV", "CIPLA", "NESTLEIND",
    "DRREDDY", "APOLLOHOSP", "EICHERMOT", "BRITANNIA", "HEROMOTOCO",
    "BPCL", "INDUSINDBK", "HDFCLIFE", "SBILIFE", "TATACONSUM",
    "UPL", "HINDALCO", "BAJAJ-AUTO", "DABUR", "GODREJCP",
    "HAVELLS", "TRENT", "IRCTC", "PFC", "SHRIRAMFIN",
    "MAXHEALTH", "NAUKRI", "CANBK", "ICICIPRULI", "VEDL",
    "ATGL", "ADANIGREEN", "TATAPOWER", "TIINDIA", "FEDERALBNK"
]
NIFTY_50 = list(set(NIFTY_50))

matched = {s: nse_map[s] for s in NIFTY_50 if s in nse_map}
not_found = [s for s in NIFTY_50 if s not in nse_map]
print(f"Matched: {len(matched)}, Not found: {not_found}")

# Delete existing daily data
subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c \"DELETE FROM candle_data WHERE timeframe = 'daily';\""],
    capture_output=True, text=True
)
print("Existing daily data cleared")

# Fetch 3yr data
end_date = datetime.now().strftime("%Y-%m-%d")
start_date = (datetime.now() - timedelta(days=3*365)).strftime("%Y-%m-%d")
print(f"\nFetching: {start_date} to {end_date}")

total = 0
errors = 0
csv_lines = []
items = list(matched.items())

for i, (sym, token) in enumerate(items):
    print(f"[{i+1}/{len(items)}] {sym}...", end=" ", flush=True)
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
    time.sleep(0.25)

print(f"\nTotal: {total} candles, {errors} errors")

# Write CSV
csv_file = "/tmp/daily_candles_3yr_zerodha.csv"
with open(csv_file, "w") as f:
    f.write("\n".join(csv_lines))
print(f"CSV: {len(csv_lines)} lines")

# Load into DB
if csv_lines:
    with open("/tmp/load_zerodha.sql", "w") as f:
        f.write(f"COPY candle_data(symbol, timeframe, timestamp, open, high, low, close, volume) FROM '{csv_file}' WITH DELIMITER '|';\n")
    result = subprocess.run(
        ['bash', '-c', 'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -f /tmp/load_zerodha.sql'],
        capture_output=True, text=True
    )
    print(f"Load: {result.stdout.strip()}")
    if result.stderr and 'ERROR' in result.stderr:
        print(f"Load error: {result.stderr.strip()[:300]}")

# Verify
result = subprocess.run(
    ['bash', '-c', "PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT MIN(timestamp)::date, MAX(timestamp)::date, COUNT(DISTINCT symbol), COUNT(*) FROM candle_data WHERE timeframe='daily';\""],
    capture_output=True, text=True
)
print(f"\nDB: {result.stdout.strip()}")

