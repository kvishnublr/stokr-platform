import urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "OVERSOLD_BOUNCE",
    "universe": "NIFTY_50",
    "dateStart": "2026-05-01",
    "dateEnd": "2026-05-30",
    "capital": "25000"
}).encode()
req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=120) as resp:
    import json
    result = json.loads(resp.read())
    print(f"strategy: {result.get('strategy')}")
    print(f"symbolsLoaded: {result.get('symbolsLoaded')}")
    print(f"totalTrades: {result.get('totalTrades')}")
    print(f"candlesLoaded: {result.get('candlesLoaded')}")
    trades = result.get("trades", [])
    for t in trades[:3]:
        print(f"  {t['symbol']} entry={t.get('entryTime','?')} exit={t.get('exitTime','?')}")
    if not trades:
        print("  NO TRADES")
