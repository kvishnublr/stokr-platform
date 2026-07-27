#!/usr/bin/env python3
import requests

token = "WrWJGeh3JwVuNvT7lUXuZZLpVCcug1q9"
API_KEY = "`$ZERODHA_API_KEY"
headers = {"Authorization": f"token {API_KEY}:{token}", "X-Kite-Version": "3"}

# With trailing slash
r1 = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print(f"With slash: {r1.status_code} {r1.text[:200]}")

# Without trailing slash
r2 = requests.get("https://api.kite.trade/quote?i=NSE:NIFTY%2050", headers=headers)
print(f"Without slash: {r2.status_code} {r2.text[:200]}")

# With space encoding
r3 = requests.get("https://api.kite.trade/quote?i=NSE:NIFTY 50", headers=headers)
print(f"Space (not encoded): {r3.status_code} {r3.text[:200]}")

# Different User-Agent
h2 = dict(headers)
h2["User-Agent"] = "Mozilla/5.0"
r4 = requests.get("https://api.kite.trade/quote?i=NSE:NIFTY%2050", headers=h2)
print(f"Mozilla UA: {r4.status_code} {r4.text[:200]}")

