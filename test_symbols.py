#!/usr/bin/env python3
import requests, json, psycopg2

conn = psycopg2.connect("host=localhost dbname=stokr_lite user=postgres password=`$POSTGRES_PASSWORD")
cur = conn.cursor()
cur.execute("SELECT access_token FROM broker_accounts WHERE id = 4")
TOKEN = cur.fetchone()[0]
cur.close()
conn.close()

HEADERS = {"Authorization": "token `$ZERODHA_API_KEY:" + TOKEN, "X-Kite-Version": "3"}

# Test different NIFTY option symbol formats
test_symbols = [
    "NFO:NIFTY2672224000CE",   # YY=26 M=7 DD=22
    "NFO:NIFTY26JUL24000CE",   # Monthly format
    "NFO:NIFTY 26JUL24000CE",  # With space
    "NFO:NIFTY22JUL24000CE",   # DD first
    "NFO:NIFTY267224000CE",    # No day
]

for sym in test_symbols:
    params = [("i", sym)]
    resp = requests.get("https://api.kite.trade/quote", headers=HEADERS, params=params, timeout=15)
    d = resp.json()
    data = d.get("data", {})
    found = len(data) > 0 and not data.get("data", {}).get("status") == "error"
    if data:
        for k, v in data.items():
            lp = v.get("last_price", 0) if isinstance(v, dict) else 0
            print(f"  {sym} -> key={k} last_price={lp}")
    else:
        print(f"  {sym} -> NOT FOUND (data={str(d)[:100]})")

# Also try fetching a known BANKNIFTY future that worked today
print("\n--- Known good symbols ---")
params2 = [("i", "NFO:BANKNIFTY26JULFUT"), ("i", "NFO:MIDCPNIFTY26JULFUT"), ("i", "NFO:FINNIFTY26JULFUT")]
resp2 = requests.get("https://api.kite.trade/quote", headers=HEADERS, params=params2, timeout=15)
d2 = resp2.json().get("data", {})
for k, v in d2.items():
    lp = v.get("last_price", 0) if isinstance(v, dict) else 0
    print(f"  {k}: last_price={lp}")

