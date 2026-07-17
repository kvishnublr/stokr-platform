import urllib.request, urllib.parse, json

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "EMA50_DISTANCE",
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
    print(f"netPnL: {result.get('netPnL')}")
    print(f"winRate: {result.get('winRate')}")
    trades = result.get("trades", [])
    for t in trades:
        print(f"  {t['symbol']:12s} entry={t.get('entryTime','?')[:10]} exit={t.get('exitTime','?')[:10]} pnl={t.get('netPnl',0):+.0f} {t.get('exitType')}")
