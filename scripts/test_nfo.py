#!/usr/bin/env python3
import requests

token = "WrWJGeh3JwVuNvT7lUXuZZLpVCcug1q9"
API_KEY = "`$ZERODHA_API_KEY"
headers = {"Authorization": f"token {API_KEY}:{token}", "X-Kite-Version": "3"}

# NFO Futures
r1 = requests.get("https://api.kite.trade/quote?i=NFO:NIFTY26JULFUT", headers=headers)
print(f"NFO:NIFTY26JULFUT: {r1.status_code}")
data = r1.json()
if "data" in data and "NFO:NIFTY26JULFUT" in data["data"]:
    q = data["data"]["NFO:NIFTY26JULFUT"]
    print(f"  last_price: {q.get('last_price')}")
    print(f"  oi: {q.get('oi')}")
    print(f"  volume: {q.get('volume')}")
else:
    print(f"  Response: {r1.text[:300]}")

# Also test the option with depth
r2 = requests.get("https://api.kite.trade/quote?i=NFO:NIFTY2672124550CE", headers=headers)
print(f"\nNFO:NIFTY2672124550CE: {r2.status_code}")
data2 = r2.json()
if "data" in data2:
    for key, q in data2["data"].items():
        print(f"  {key}: last_price={q.get('last_price')}")
        depth = q.get("depth", {})
        buy = depth.get("buy", [])
        sell = depth.get("sell", [])
        print(f"  depth.buy: {buy[:2]}")
        print(f"  depth.sell: {sell[:2]}")

