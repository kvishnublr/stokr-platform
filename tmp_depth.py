import json, urllib.request, urllib.parse

api_key = "zazlrld244cc6jf0"
# Get token from DB
import subprocess
result = subprocess.run(
    ["ssh", "root@173.249.55.84", "PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c \"SELECT access_token FROM broker_accounts LIMIT 1\""],
    capture_output=True, text=True
)
token = result.stdout.strip()
print(f"Token: {token[:20]}...")

# Fetch a quote for a BANKNIFTY option
url = f"https://api.kite.trade/quote?i=NFO:BANKNIFTY26JUL59100PE"
req = urllib.request.Request(url)
req.add_header("Authorization", f"token {api_key}:{token}")
req.add_header("X-Kite-Version", "3")

try:
    resp = urllib.request.urlopen(req, timeout=10)
    data = json.loads(resp.read())
    # Print the depth structure
    for instrument, quote_data in data.get("data", {}).items():
        print(f"\nInstrument: {instrument}")
        print(f"  last_price: {quote_data.get('last_price')}")
        depth = quote_data.get("depth", {})
        buy = depth.get("buy", [])
        sell = depth.get("sell", [])
        print(f"  depth.buy type: {type(buy).__name__}, len: {len(buy) if isinstance(buy, list) else 'N/A'}")
        if isinstance(buy, list) and len(buy) > 0:
            print(f"  depth.buy[0]: {buy[0]}")
        print(f"  depth.sell type: {type(sell).__name__}, len: {len(sell) if isinstance(sell, list) else 'N/A'}")
        if isinstance(sell, list) and len(sell) > 0:
            print(f"  depth.sell[0]: {sell[0]}")
except Exception as e:
    print(f"Error: {e}")
