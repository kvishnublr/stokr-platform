#!/usr/bin/env python3
"""Fetch 3 years of daily data using yfinance (free, no auth)"""
import subprocess
import time

# Install yfinance if not present
subprocess.run(['pip3', 'install', 'yfinance', '-q'], capture_output=True)

import yfinance as yf
import json

# NIFTY_50 symbols with .NS suffix for Yahoo Finance
SYMBOLS = [
    "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "INFY.NS", "ICICIBANK.NS",
    "SBIN.NS", "BHARTIARTL.NS", "ITC.NS", "KOTAKBANK.NS", "LT.NS",
    "HINDUNILVR.NS", "AXISBANK.NS", "MARUTI.NS", "BAJFINANCE.NS", "ASIANPAINT.NS",
    "SUNPHARMA.NS", "TITAN.NS", "ULTRACEMCO.NS", "WIPRO.NS", "HCLTECH.NS",
    "TATAMOTORS.NS", "ONGC.NS", "NTPC.NS", "POWERGRID.NS", "ADANIPORTS.NS",
    "JSWSTEEL.NS", "TATASTEEL.NS", "COALINDIA.NS", "M&M.NS", "TECHM.NS",
    "ADANIENT.NS", "GRASIM.NS", "BAJAJFINSV.NS", "CIPLA.NS", "NESTLEIND.NS",
    "DRREDDY.NS", "APOLLOHOSP.NS", "EICHERMOT.NS", "BRITANNIA.NS", "HEROMOTOCO.NS",
    "BPCL.NS", "INDUSINDBK.NS", "HDFCLIFE.NS", "SBILIFE.NS", "TATACONSUM.NS",
    "UPL.NS", "HINDALCO.NS", "BAJAJ-AUTO.NS", "DABUR.NS", "GODREJCP.NS",
    "HAVELLS.NS", "TRENT.NS", "IRCTC.NS", "PFC.NS", "SHRIRAMFIN.NS",
    "MAXHEALTH.NS", "NAUKRI.NS", "CANBK.NS", "ICICIPRULI.NS", "VEDL.NS",
    "ATGL.NS", "ADANIGREEN.NS", "TATAPOWER.NS", "TIINDIA.NS", "SBIN.NS"
]
SYMBOLS = list(set(SYMBOLS))  # dedupe

# Strip .NS for DB storage
def to_symbol(yf_sym):
    return yf_sym.replace(".NS", "")

def save_to_db(symbol, rows):
    """Save using psql COPY for speed"""
    # Write CSV temp file
    csv_lines = []
    for _, row in rows.iterrows():
        ts = str(row.name).replace(" ", "T") + "+05:30"
        o, h, l, c, v = row['Open'], row['High'], row['Low'], row['Close'], int(row['Volume'])
        csv_lines.append(f"{symbol}|daily|{ts}|{o}|{h}|{l}|{c}|{v}")
    
    if not csv_lines:
        return 0
    
    # Write to temp file
    with open(f"/tmp/candles_{symbol}.csv", "w") as f:
        f.write("\n".join(csv_lines))
    
    # Use psql COPY for bulk insert
    sql = f"""
    COPY candle_data(symbol, timeframe, timestamp, open, high, low, close, volume) 
    FROM stdin WITH DELIMITER '|';
    """
    cmd = f"PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"{sql}\" < /tmp/candles_{symbol}.csv"
    result = subprocess.run(['bash', '-c', cmd], capture_output=True, text=True)
    
    return len(csv_lines)

# Alternative: use INSERT batch
def save_to_db_batch(symbol, rows):
    """Save using individual INSERTs"""
    count = 0
    values = []
    for _, row in rows.iterrows():
        ts = str(row.name).replace(" ", "T") + "+05:30"
        o, h, l, c, v = row['Open'], row['High'], row['Low'], row['Close'], int(row['Volume'])
        values.append(f"('{symbol}', 'daily', '{ts}', {o}, {h}, {l}, {c}, {v})")
    
    if not values:
        return 0
    
    # Batch insert (50 at a time)
    batch_size = 50
    for i in range(0, len(values), batch_size):
        batch = values[i:i+batch_size]
        sql = f"""INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume) 
                  VALUES {','.join(batch)}
                  ON CONFLICT (symbol, timeframe, timestamp) DO UPDATE SET
                    open=EXCLUDED.open, high=EXCLUDED.high, low=EXCLUDED.low, close=EXCLUDED.close, volume=EXCLUDED.volume;"""
        
        # Escape for shell
        sql_escaped = sql.replace("'", "\\'")
        cmd = f"PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c \"{sql_escaped}\""
        result = subprocess.run(['bash', '-c', cmd], capture_output=True, text=True)
        if result.returncode != 0:
            print(f"  DB error: {result.stderr[:200]}")
        count += batch_size
    
    return len(values)

print(f"Fetching 3 years of daily data (Jul 2023 - Jul 2026) for {len(SYMBOLS)} symbols...")
print(f"Using Yahoo Finance (free, no auth needed)")
print()

total_saved = 0
for i, yf_sym in enumerate(SYMBOLS):
    symbol = to_symbol(yf_sym)
    print(f"[{i+1}/{len(SYMBOLS)}] {symbol}...", end=" ", flush=True)
    
    try:
        ticker = yf.Ticker(yf_sym)
        # Download 3 years of daily data
        data = ticker.history(period="3y", interval="1d")
        
        if data.empty:
            print("No data")
            continue
        
        # Save to DB
        saved = save_to_db_batch(symbol, data)
        total_saved += saved
        print(f"{len(data)} candles")
        
    except Exception as e:
        print(f"Error: {e}")
    
    # Small delay to be nice to Yahoo
    time.sleep(0.3)

print(f"\nDone! Total candles saved: {total_saved}")

# Verify
result = subprocess.run(['bash', '-c', 
    'PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT MIN(timestamp)::date, MAX(timestamp)::date, COUNT(*) FROM candle_data WHERE timeframe=\'daily\' LIMIT 1;"'],
    capture_output=True, text=True)
print(f"Database: {result.stdout.strip()}")
