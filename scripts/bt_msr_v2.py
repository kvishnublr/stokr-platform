import json, urllib.request, urllib.parse

url = "http://localhost:8081/api/backtest/advanced"
data = urllib.parse.urlencode({
    "strategyName": "MORNING_SURGE_REVERSAL",
    "universe": "NIFTY_100",
    "startDate": "2026-04-10",
    "endDate": "2026-07-10",
    "capital": "33000",
    "initialCapital": "100000"
}).encode()

req = urllib.request.Request(url, data=data, method="POST")
with urllib.request.urlopen(req) as resp:
    result = json.loads(resp.read())

print("=== MSR v2 BACKTEST (3 months, NIFTY_100, Rs 33K) ===")
print(f"Trades: {result.get('totalTrades')}")
print(f"Win Rate: {result.get('winRate')}")
print(f"Net P&L: {result.get('netPnl')}")
print(f"Profit Factor: {result.get('profitFactor')}")
print(f"Max Drawdown: {result.get('maxDrawdown')}")
print(f"Wins: {result.get('winningTrades')}, Losses: {result.get('losingTrades')}")
print(f"Avg Win: {result.get('avgWin')}")
print(f"Avg Loss: {result.get('avgLoss')}")
print(f"Sharpe: {result.get('sharpeRatio')}")
print(f"Max Consec Loss: {result.get('maxConsecutiveLosses')}")
print(f"Profitable Months: {result.get('profitableMonths')}")
print(f"Total Months: {result.get('totalMonths')}")

# Print each trade
if result.get('trades'):
    print("\n=== TRADES ===")
    for t in result['trades']:
        print(f"  {t.get('symbol'):15s} {t.get('side'):5s} entry={t.get('entryPrice')} exit={t.get('exitPrice')} pnl={t.get('pnl')} exitType={t.get('exitType')} hold={t.get('holdDays')}d reason={t.get('exitReason','')}")
