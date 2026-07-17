import json, urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "GAP_CONTINUATION",
    "universe": "NIFTY_100",
    "dateStart": "2026-04-10",
    "dateEnd": "2026-07-10",
    "capital": "33000",
    "initialCapital": "100000"
}).encode()
req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=180) as resp:
    result = json.loads(resp.read())

# Print all top-level keys to find the right field name
for k, v in result.items():
    if k != "trades":
        print(f"  {k}: {v}")
    else:
        print(f"  trades: [{len(v)} items]")
        if v:
            print(f"  sample trade keys: {list(v[0].keys())}")
