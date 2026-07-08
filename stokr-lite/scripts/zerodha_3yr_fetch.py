#!/usr/bin/env python3
"""Test Zerodha API + fetch 3yr daily data"""
import urllib.request
import json
import time

# Load credentials
creds = {}
with open("/opt/stokr/stokr-lite.env") as f:
    for line in f:
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            creds[k.strip()] = v.strip()

API_KEY = creds.get("ZERODHA_API_KEY", "")
ACCESS_TOKEN = creds.get("ZERODHA_ACCESS_TOKEN", "")

print(f"API_KEY: {API_KEY[:8]}...")
print(f"ACCESS_TOKEN: {ACCESS_TOKEN[:8]}..." if ACCESS_TOKEN else "ACCESS_TOKEN: EMPTY")

# Test 1: Check profile (validate token)
print("\n=== Testing Zerodha API ===")
try:
    url = "https://api.kite.trade/quote/user"
    req = urllib.request.Request(url)
    req.add_header("X-Kite-Version", "3")
    req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
        print(f"Profile OK: {json.dumps(data, indent=2)[:300]}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"Profile FAILED: {e.code} - {body[:200]}")
except Exception as e:
    print(f"Profile ERROR: {e}")

# Test 2: Check instruments
print("\n=== Checking instruments availability ===")
try:
    url = "https://api.kite.trade/instruments/NFO"
    req = urllib.request.Request(url)
    req.add_header("X-Kite-Version", "3")
    req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        print(f"Instruments: {len(resp.read())} bytes - OK")
except urllib.error.HTTPError as e:
    print(f"Instruments: {e.code}")
except Exception as e:
    print(f"Instruments ERROR: {e}")

# Test 3: Historical data for 1 day (RELIANCE)
print("\n=== Testing historical data ===")
try:
    url = f"https://api.kite.trade/instruments/historical/738561/day?from=2026-07-01&to=2026-07-07"
    req = urllib.request.Request(url)
    req.add_header("X-Kite-Version", "3")
    req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
        candles = data.get("data", {}).get("candles", [])
        print(f"RELIANCE candles: {len(candles)} - {candles[0] if candles else 'none'}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"Historical FAILED: {e.code} - {body[:200]}")
except Exception as e:
    print(f"Historical ERROR: {e}")

# Test 4: Fetch 3yr daily data for NIFTY_50
NIFTY_50 = [
    (738561, "RELIANCE"), (256265, "TCS"), (341249, "HDFCBANK"), (408065, "INFY"),
    (1510401, "ICICIBANK"), (304001, "SBIN"), (680961, "BHARTIARTL"), (1099041, "ITC"),
    (492033, "KOTAKBANK"), (1154753, "LT"), (356865, "HINDUNILVR"), (590081, "AXISBANK"),
    (779521, "MARUTI"), (4269313, "BAJFINANCE"), (6311041, "ASIANPAINT"),
    (779521, "MARUTI"), (2884225, "SUNPHARMA"), (850113, "TITAN"),
    (723257, "ULTRACEMCO"), (1121281, "WIPRO"), (1121281, "WIPRO"),
    (875329, "HCLTECH"), (884737, "TATAMOTORS"), (6336001, "ONGC"),
    (2730497, "NTPC"), (2714625, "POWERGRID"), (2178433, "ADANIPORTS"),
    (11575937, "JSWSTEEL"), (1346337, "TATASTEEL"), (13236577, "COALINDIA"),
    (4749825, "M&M"), (356865, "HINDUNILVR"), (3660033, "TECHM"),
    (6966529, "ADANIENT"), (2192321, "GRASIM"), (4269313, "BAJFINANCE"),
    (2714625, "POWERGRID"), (6401, "NESTLEIND"), (6311041, "ASIANPAINT"),
    (1070401, "DRREDDY"), (356865, "HINDUNILVR"), (1896833, "EICHERMOT"),
    (1229825, "BRITANNIA"), (356865, "HINDUNILVR"), (680961, "BHARTIARTL"),
    (1030401, "BPCL"), (1346337, "TATASTEEL"), (1121281, "WIPRO"),
    (304001, "SBIN"), (1756257, "HDFCLIFE"), (3660033, "TECHM"),
    (1154753, "LT"), (256265, "TCS"), (6798561, "SBILIFE"),
    (6401, "NESTLEIND"), (2641601, "CIPLA"), (2953217, "FEDERALBNK")
]

# Deduplicate
seen = set()
unique = []
for tsid, sym in NIFTY_50:
    if sym not in seen:
        seen.add(sym)
        unique.append((tsid, sym))

print(f"\nWill fetch {len(unique)} unique symbols...")

# Fetch 3yr data
from datetime import datetime, timedelta
end_date = datetime.now().strftime("%Y-%m-%d")
start_date = (datetime.now() - timedelta(days=3*365)).strftime("%Y-%m-%d")
print(f"Date range: {start_date} to {end_date}")

total = 0
errors = 0
csv_lines = []
for i, (tsid, sym) in enumerate(unique):
    print(f"[{i+1}/{len(unique)}] {sym} (token={tsid})...", end=" ", flush=True)
    try:
        url = f"https://api.kite.trade/instruments/historical/{tsid}/day?from={start_date}&to={end_date}"
        req = urllib.request.Request(url)
        req.add_header("X-Kite-Version", "3")
        req.add_header("Authorization", f"token {API_KEY}:{ACCESS_TOKEN}")
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read())
            candles = data.get("data", {}).get("candles", [])
            for c in candles:
                # c = [timestamp, open, high, low, close, volume, ...]
                ts = str(c[0]).replace("T", " ")[:19]
                csv_lines.append(f"{sym}|daily|{ts}|{c[1]}|{c[2]}|{c[3]}|{c[4]}|{int(c[5])}")
            total += len(candles)
            print(f"{len(candles)} candles")
    except Exception as e:
        errors += 1
        print(f"ERROR: {e}")
    time.sleep(0.3)  # Rate limit

print(f"\nTotal: {total} candles, {errors} errors")

# Write CSV
csv_file = "/tmp/daily_candles_3yr_zerodha.csv"
with open(csv_file, "w") as f:
    f.write("\n".join(csv_lines))
print(f"CSV written to {csv_file}: {len(csv_lines)} lines")
