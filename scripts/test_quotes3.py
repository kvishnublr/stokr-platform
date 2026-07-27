#!/usr/bin/env python3
import requests

token = "WrWJGeh3JwVuNvT7lUXuZZLpVCcug1q9"
API_KEY = "`$ZERODHA_API_KEY"
headers = {"Authorization": f"token {API_KEY}:{token}", "X-Kite-Version": "3"}

r = requests.get("https://api.kite.trade/quote/?i=NFO:NIFTY26JULFUT", headers=headers)
print(f"FUT status: {r.status_code}")
print(f"FUT: {r.text[:400]}")

r2 = requests.get("https://api.kite.trade/quote/?i=NSE:NIFTY%2050", headers=headers)
print(f"\nSPOT status: {r2.status_code}")
print(f"SPOT: {r2.text[:400]}")

