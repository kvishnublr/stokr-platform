#!/usr/bin/env python3
"""Fetch 3 years of daily candle data from Zerodha API"""
import requests
import json
import time
from datetime import datetime, timedelta

# Load Zerodha credentials
import subprocess
result = subprocess.run(['bash', '-c', 'source /opt/stokr/stokr-lite.env && echo $ZERODHA_API_KEY'], 
                       capture_output=True, text=True)
API_KEY = result.stdout.strip()

# Get access token from database
result = subprocess.run(['bash', '-c', 
    'PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT access_token FROM brokers WHERE broker_type = \'ZERODHA\' LIMIT 1;"'],
    capture_output=True, text=True)
ACCESS_TOKEN = result.stdout.strip()

print(f"API Key: {API_KEY[:10]}...")
print(f"Access Token: {ACCESS_TOKEN[:10]}...")

# NIFTY_100 symbols (same as backtest universe)
SYMBOLS = [
    "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
    "BAJAJFINSV", "BAJFINANCE", "BHARTIARTL", "BPCL", "BRITANNIA",
    "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "GRASIM",
    "HCLTECH", "HDFCBANK", "HDFCLIFE", "HEROMOTOCO", "HINDALCO",
    "HINDUNILVR", "ICICIBANK", "INDUSINDBK", "INFY", "ITC",
    "JSWSTEEL", "KOTAKBANK", "LT", "M&M", "MARUTI",
    "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE",
    "SBIN", "SUNPHARMA", "TATAMOTORS", "TATASTEEL", "TCS",
    "TECHM", "TITAN", "ULTRACEMCO", "WIPRO", "AXISBANK",
    "BAJAJ-AUTO", "BHARTIARTL", "CIPLA", "DRREDDY", "EICHERMOT"
]
# Remove duplicates
SYMBOLS = list(set(SYMBOLS))

def fetch_daily_candles(symbol, from_date, to_date):
    """Fetch daily candles from Zerodha API"""
    url = f"https://api.kite.trade/instruments/historical/{symbol}/day"
    headers = {
        "X-Kite-Version": "3",
        "Authorization": f"token {ACCESS_TOKEN}"
    }
    params = {
        "from": from_date,
        "to": to_date
    }
    
    response = requests.get(url, headers=headers, params=params)
    if response.status_code == 200:
        data = response.json()
        return data.get("data", {}).get("candles", [])
    else:
        print(f"Error fetching {symbol}: {response.status_code} - {response.text}")
        return []

def save_candles_to_db(symbol, candles):
    """Save candles to database"""
    for candle in candles:
        timestamp = candle[0]  # "2023-07-07T00:00:00+05:30"
        open_price = candle[1]
        high = candle[2]
        low = candle[3]
        close = candle[4]
        volume = candle[5]
        
        query = f"""
        INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume)
        VALUES ('{symbol}', 'daily', '{timestamp}', {open_price}, {high}, {low}, {close}, {volume})
        ON CONFLICT (symbol, timeframe, timestamp) 
        DO UPDATE SET 
            open = EXCLUDED.open,
            high = EXCLUDED.high,
            low = EXCLUDED.low,
            close = EXCLUDED.close,
            volume = EXCLUDED.volume;
        """
        
        subprocess.run(['bash', '-c', f'PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "{query}"'],
                      capture_output=True, text=True)

# Date range: 3 years back from today
end_date = datetime.now().strftime("%Y-%m-%d")
start_date = (datetime.now() - timedelta(days=3*365)).strftime("%Y-%m-%d")

print(f"Fetching daily candles from {start_date} to {end_date}")
print(f"Symbols: {len(SYMBOLS)}")

total_candles = 0
for i, symbol in enumerate(SYMBOLS):
    print(f"[{i+1}/{len(SYMBOLS)}] Fetching {symbol}...")
    
    candles = fetch_daily_candles(symbol, start_date, end_date)
    if candles:
        save_candles_to_db(symbol, candles)
        total_candles += len(candles)
        print(f"  Saved {len(candles)} candles")
    else:
        print(f"  No data")
    
    # Rate limiting - Zerodha allows 10 requests/second
    time.sleep(0.2)

print(f"\nDone! Total candles saved: {total_candles}")

# Verify data
result = subprocess.run(['bash', '-c', 
    'PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT MIN(timestamp), MAX(timestamp), COUNT(*) FROM candle_data WHERE timeframe = \'daily\';"'],
    capture_output=True, text=True)
print(f"\nDatabase verification:\n{result.stdout}")
