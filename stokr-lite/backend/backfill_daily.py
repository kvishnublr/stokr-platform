import json, sys, time, requests, psycopg2

API_KEY = "zazlrld244cc6jf0"
API_SECRET = "iyc7m8166tb6i95gt829q6mzbzvmfq6k"
DB_HOST = "localhost"
DB_NAME = "stokr_lite"
DB_USER = "postgres"
DB_PASS = "stokr2026"

NIFTY50 = [
    "RELIANCE","TCS","HDFCBANK","ICICIBANK","INFY","HINDUNILVR","ITC","KOTAKBANK",
    "LT","SBIN","AXISBANK","BAJFINANCE","BHARTIARTL","TITAN","MARUTI","HCLTECH",
    "SUNPHARMA","TATAMOTORS","NTPC","BAJAJFINSV","WIPRO","JSWSTEEL","ONGC",
    "POWERGRID","COALINDIA","GRASIM","TATASTEEL","BPCL","HINDALCO","ULTRACEMCO",
    "ADANIENT","ADANIPORTS","APOLLOHOSP","DIVISLAB","DRREDDY","EICHERMOT",
    "HDFCLIFE","HEROMOTOCO","INDUSINDBK","M&M","NESTLEIND","SBILIFE","TATACONSUM",
    "TECHM","TRENT","DMART","UPL","CIPLA","BRITANNIA","ASIANPAINT"
]

SYMBOL_TO_TOKEN = {
    "RELIANCE":"738561","TCS":"2953217","HDFCBANK":"341249","ICICIBANK":"1270529",
    "INFY":"408065","HINDUNILVR":"356865","ITC":"424961","KOTAKBANK":"492033",
    "LT":"2939649","SBIN":"779521","AXISBANK":"1510401","BAJFINANCE":"81153",
    "BHARTIARTL":"2714625","TITAN":"897537","MARUTI":"2815745","HCLTECH":"1850625",
    "SUNPHARMA":"857857","TATAMOTORS":"884737","NTPC":"2977281","BAJAJFINSV":"54273",
    "WIPRO":"969473","JSWSTEEL":"3001089","ONGC":"633601","POWERGRID":"3834113",
    "COALINDIA":"5215745","GRASIM":"315393","TATASTEEL":"895745","BPCL":"134657",
    "HINDALCO":"348929","ULTRACEMCO":"2952193","ADANIENT":"6401","ADANIPORTS":"3861249",
    "APOLLOHOSP":"40193","DIVISLAB":"2800641","DRREDDY":"225537","EICHERMOT":"232961",
    "HDFCLIFE":"119553","HEROMOTOCO":"345089","INDUSINDBK":"1346049","M&M":"519937",
    "NESTLEIND":"4598529","SBILIFE":"5582849","TATACONSUM":"878593","TECHM":"3465729",
    "TRENT":"502785","DMART":"5097729","UPL":"2889473","CIPLA":"177665",
    "BRITANNIA":"140033","ASIANPAINT":"60417"
}

def get_access_token():
    conn = psycopg2.connect(host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASS)
    cur = conn.cursor()
    cur.execute("SELECT id, access_token, token_expiry FROM broker_accounts WHERE status='ACTIVE' ORDER BY id LIMIT 1")
    row = cur.fetchone()
    cur.close()
    conn.close()
    if row:
        print(f"Found active token for account {row[0]}, expires: {row[2]}")
        return row[1]
    return None

def fetch_daily_candles(token, symbol, from_date, to_date):
    instr_token = SYMBOL_TO_TOKEN.get(symbol)
    if not instr_token:
        print(f"  No token for {symbol}, skipping")
        return []
    
    url = f"https://api.kite.trade/instruments/historical/{instr_token}/day?from={from_date}&to={to_date}"
    headers = {
        "X-Kite-Version": "3",
        "Authorization": f"token {API_KEY}:{token}"
    }
    
    all_candles = []
    resp = requests.get(url, headers=headers, timeout=30)
    if resp.status_code != 200:
        print(f"  {symbol}: HTTP {resp.status_code} - {resp.text[:200]}")
        return []
    
    data = resp.json()
    candles = data.get("candles", [])
    for c in candles:
        ts = c[0]
        if "T" in ts:
            from datetime import datetime
            from zoneinfo import ZoneInfo
            dt = datetime.fromisoformat(ts)
            ist = dt.astimezone(ZoneInfo("Asia/Kolkata"))
            ts = ist.strftime("%Y-%m-%d %H:%M:%S")
        
        all_candles.append({
            "symbol": symbol,
            "timeframe": "daily",
            "timestamp": ts,
            "open": float(c[1]),
            "high": float(c[2]),
            "low": float(c[3]),
            "close": float(c[4]),
            "volume": int(c[5])
        })
    
    print(f"  {symbol}: {len(all_candles)} daily candles")
    return all_candles

def save_to_db(candles):
    if not candles:
        return
    conn = psycopg2.connect(host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASS)
    cur = conn.cursor()
    saved = 0
    for c in candles:
        try:
            cur.execute("""
                INSERT INTO candle_data (symbol, timeframe, timestamp, open, high, low, close, volume)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT DO NOTHING
            """, (c["symbol"], c["timeframe"], c["timestamp"], c["open"], c["high"], c["low"], c["close"], c["volume"]))
            saved += 1
        except Exception as e:
            pass
    conn.commit()
    print(f"  Saved {saved} candles to DB")
    cur.close()
    conn.close()

def main():
    token = get_access_token()
    if not token:
        print("ERROR: No active Zerodha token found")
        sys.exit(1)
    
    from_date = "2025-07-06"
    to_date = "2026-07-06"
    
    print(f"Fetching daily candles from {from_date} to {to_date}")
    print(f"Symbols: {len(NIFTY50)}")
    
    total = 0
    for i, sym in enumerate(NIFTY50):
        print(f"[{i+1}/{len(NIFTY50)}] {sym}")
        candles = fetch_daily_candles(token, sym, from_date, to_date)
        if candles:
            save_to_db(candles)
            total += len(candles)
        
        if (i + 1) % 5 == 0:
            time.sleep(1)
    
    print(f"\nDone! Total candles fetched: {total}")

if __name__ == "__main__":
    main()
