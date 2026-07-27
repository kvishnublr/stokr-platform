#!/usr/bin/env python3
"""Fetch 3 years of daily data using yfinance, save as CSV, then COPY to DB"""
import subprocess
import time
import os

try:
    import yfinance as yf
except ImportError:
    subprocess.run(['pip3', 'install', 'yfinance', '-q'])
    import yfinance as yf

SYMBOLS = [
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
    "ATGL", "ADANIGREEN", "TATAPOWER", "TIINDIA"
]
SYMBOLS = list(set(SYMBOLS))

CSV_FILE = "/tmp/daily_candles_3yr.csv"

# Clear existing old data and write new CSV
with open(CSV_FILE, "w") as f:
    pass  # empty file to start

total = 0
for i, sym in enumerate(SYMBOLS):
    print(f"[{i+1}/{len(SYMBOLS)}] {sym}...", end=" ", flush=True)
    try:
        ticker = yf.Ticker(f"{sym}.NS")
        data = ticker.history(period="3y", interval="1d")
        if data.empty:
            print("No data")
            continue
        
        with open(CSV_FILE, "a") as f:
            for idx, row in data.iterrows():
                ts = str(idx).replace(" ", "T")[:19]
                f.write(f"{sym}|daily|{ts}|{row['Open']:.2f}|{row['High']:.2f}|{row['Low']:.2f}|{row['Close']:.2f}|{int(row['Volume'])}\n")
        
        total += len(data)
        print(f"{len(data)} candles")
    except Exception as e:
        print(f"Error: {e}")
    time.sleep(0.3)

print(f"\nTotal candles in CSV: {total}")

# Now bulk load using psql COPY
print("\nLoading into PostgreSQL...")
load_sql = """COPY candle_data(symbol, timeframe, timestamp, open, high, low, close, volume) 
FROM '/tmp/daily_candles_3yr.csv' WITH DELIMITER '|';"""

# Write SQL file
with open("/tmp/load_candles.sql", "w") as f:
    f.write("DELETE FROM candle_data WHERE timeframe = 'daily' AND timestamp < '2025-07-07';\n")
    f.write(load_sql + "\n")

result = subprocess.run(
    ['bash', '-c', 'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -f /tmp/load_candles.sql'],
    capture_output=True, text=True
)
print(result.stdout[-500:] if result.stdout else "")
if result.stderr:
    print("STDERR:", result.stderr[-500:])

# Verify
result = subprocess.run(
    ['bash', '-c', 'PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT MIN(timestamp)::date, MAX(timestamp)::date, COUNT(DISTINCT symbol), COUNT(*) FROM candle_data WHERE timeframe=\'daily\' LIMIT 1;"'],
    capture_output=True, text=True
)
print(f"\nDatabase: {result.stdout.strip()}")

