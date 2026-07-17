import json, urllib.request, urllib.parse

def run_backtest(strategy, universe, start, end, capital=33000):
    url = "http://localhost:8081/api/backtest/advanced"
    data = urllib.parse.urlencode({
        "strategy": strategy,
        "universe": universe,
        "dateStart": start,
        "dateEnd": end,
        "capital": str(capital),
        "initialCapital": "100000",
        "timeframe": "daily"
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read())

def print_results(label, result):
    print(f"\n{'='*80}")
    print(f"  {label}")
    print(f"{'='*80}")
    print(f"  Trades:        {result.get('totalTrades')}")
    wr = result.get('winRate')
    print(f"  Win Rate:      {wr}%" if wr else f"  Win Rate:      N/A")
    print(f"  Net P&L:       Rs {result.get('netPnl')}")
    print(f"  Profit Factor: {result.get('profitFactor')}")
    print(f"  Max Drawdown:  Rs {result.get('maxDrawdown')}")
    print(f"  Avg Win:       Rs {result.get('avgWin')}")
    print(f"  Avg Loss:      Rs {result.get('avgLoss')}")
    print(f"  Sharpe:        {result.get('sharpeRatio')}")
    print(f"  Max Consec:    {result.get('maxConsecutiveLosses')}")
    print(f"  Profit Months: {result.get('profitableMonths')}")
    print(f"  Total Months:  {result.get('totalMonths')}")

    trades = result.get('trades', [])
    if trades:
        print(f"\n  {'SYMBOL':15s} {'SIDE':5s} {'ENTRY':>10s} {'EXIT':>10s} {'PNL':>10s} {'EXIT_TYPE':12s} {'HOLD':>5s}")
        print(f"  {'-'*75}")
        wins = 0
        losses = 0
        total_win = 0
        total_loss = 0
        for t in trades:
            pnl = t.get('pnl', 0) or 0
            symbol = t.get('symbol', '?')
            side = t.get('side', '?')
            entry = t.get('entryPrice', 0)
            exit_p = t.get('exitPrice', 0)
            exit_type = t.get('exitType', '?')
            hold = t.get('holdDays', '?')
            if pnl > 0:
                wins += 1
                total_win += pnl
            else:
                losses += 1
                total_loss += pnl
            print(f"  {symbol:15s} {side:5s} {entry:>10.2f} {exit_p:>10.2f} {pnl:>+10.2f} {exit_type:12s} {str(hold)+'d':>5s}")
        print(f"\n  SUMMARY: {wins}W / {losses}L | Total Win: Rs {total_win:,.2f} | Total Loss: Rs {total_loss:,.2f}")
        net = total_win + total_loss
        print(f"  Net: Rs {net:,.2f} (before brokerage)")
        if wins + losses > 0:
            print(f"  Avg Win: Rs {total_win/wins:,.2f} | Avg Loss: Rs {total_loss/losses:,.2f}")

# Clear cache first
print("Clearing backtest cache...")
import urllib.request as ur
try:
    ur.urlopen(ur.Request("http://localhost:8081/api/backtest/cache/clear", method="POST"), timeout=10)
except:
    pass

print("Running RSI OVERSOLD v2 backtests...")

# 3-month
r3 = run_backtest("RSI_OVERSOLD", "NIFTY_100", "2026-04-10", "2026-07-10")
print_results("RSI OVERSOLD v2 — 3 MONTHS (Apr 10 - Jul 10, 2026) — NIFTY_100, Rs 33K", r3)

# 6-month
r6 = run_backtest("RSI_OVERSOLD", "NIFTY_100", "2026-01-10", "2026-07-10")
print_results("RSI OVERSOLD v2 — 6 MONTHS (Jan 10 - Jul 10, 2026) — NIFTY_100, Rs 33K", r6)
