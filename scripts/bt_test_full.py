import urllib.request, urllib.parse, json

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "OVERSOLD_BOUNCE",
    "universe": "NIFTY_100",
    "dateStart": "2026-05-01",
    "dateEnd": "2026-07-12",
    "capital": "25000"
}).encode()
req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=180) as resp:
    result = json.loads(resp.read())
    print(f"symbolsLoaded: {result.get('symbolsLoaded')}")
    print(f"totalTrades: {result.get('totalTrades')}")
    print(f"candlesLoaded: {result.get('candlesLoaded')}")
    print(f"dateRange: {result.get('dateRange')}")
    trades = result.get("trades", [])
    for t in trades[:5]:
        print(f"  {t['symbol']} entry={t.get('entryTime','?')[:10]} exit={t.get('exitTime','?')[:10]} type={t.get('exitType')}")
    if len(trades) > 5:
        print(f"  ... {len(trades)-5} more")
