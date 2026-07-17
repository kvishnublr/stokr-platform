import json, urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategyName": "RSI_OVERSOLD",
    "universe": "NIFTY_100",
    "startDate": "2026-04-10",
    "endDate": "2026-07-10",
    "capital": "33000",
    "initialCapital": "100000",
    "nocache": "1"
}).encode()

req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=120) as resp:
    result = json.loads(resp.read())

print("Strategy:", result.get("strategyName", "?"))
print("Trades:", result.get("totalTrades", "?"))
trades = result.get("trades", [])
if trades:
    for t in trades[:5]:
        print(f"  {t.get('symbol'):15s} {t.get('side'):5s} entry={t.get('entryPrice')} exit={t.get('exitPrice')} pnl={t.get('pnl')} type={t.get('exitType')}")
    print(f"  ... and {len(trades)-5} more")
else:
    print("NO TRADES")
