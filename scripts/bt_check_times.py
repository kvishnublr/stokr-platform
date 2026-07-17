import json, urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "OVERSOLD_BOUNCE",
    "universe": "NIFTY_100",
    "dateStart": "2026-06-01",
    "dateEnd": "2026-06-10",
    "capital": "25000",
    "initialCapital": "100000"
}).encode()
req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=120) as resp:
    result = json.loads(resp.read())

for t in result.get("trades", [])[:3]:
    print(f"symbol={t['symbol']}")
    print(f"  entryTime={t.get('entryTime')}")
    print(f"  exitTime ={t.get('exitTime')}")
