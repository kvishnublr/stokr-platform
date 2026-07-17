import json, urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategy": "OVERSOLD_BOUNCE",
    "universe": "NIFTY_100",
    "dateStart": "2026-06-01",
    "dateEnd": "2026-07-10",
    "capital": "25000",
    "initialCapital": "100000"
}).encode()
req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req, timeout=180) as resp:
    result = json.loads(resp.read())

trades = result.get("trades", [])
print(f"{'#':>3s} {'Symbol':15s} {'Side':5s} {'Entry':>10s} {'Exit':>10s} {'Net PnL':>10s} {'MaxLoss':>10s} {'MaxProfit':>10s} {'ExitType':12s} {'ExitTime'}")
print("-" * 140)
for i, t in enumerate(trades, 1):
    print(f"{i:3d} {t['symbol']:15s} {t['side']:5s} {t['entryPrice']:>10} {t.get('exitPrice',0):>10} {t['netPnl']:>+10.2f} {t.get('maxUnrealizedLoss',0):>+10.2f} {t.get('maxUnrealizedProfit',0):>+10.2f} {t['exitType']:12s} {t.get('exitTime','?')}")
