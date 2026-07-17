import json, urllib.request, urllib.parse

def run_bt(strategy, start, end, capital=25000):
    url = "http://localhost:8081/api/backtest/advanced"
    data = urllib.parse.urlencode({
        "strategy": strategy,
        "universe": "NIFTY_100",
        "dateStart": start,
        "dateEnd": end,
        "capital": str(capital),
        "initialCapital": "100000"
    }).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read())

strategies = [
    ("OVERSOLD_BOUNCE", "OB"),
    ("EMA50_DISTANCE", "EMA50D"),
    ("THREE_RED_DAYS", "TRD"),
    ("RSI_OVERSOLD", "RSIO"),
]

for stype, short in strategies:
    r = run_bt(stype, "2026-04-10", "2026-07-10")
    trades = r.get("trades", [])
    print(f"\n{'='*150}")
    print(f"  {short} ({stype}) — 3 MONTHS — {len(trades)} trades")
    print(f"{'='*150}")
    print(f"  {'#':>3s} {'Symbol':15s} {'Side':5s} {'Entry':>10s} {'Exit':>10s} {'Net PnL':>10s} {'MaxLoss':>10s} {'MaxProfit':>10s} {'ExitType':12s} {'EntryTime':20s} {'ExitTime':20s}")
    print(f"  {'-'*145}")
    for i, t in enumerate(trades, 1):
        entry_time = t.get('entryTime', '?')[:16] if t.get('entryTime') else '?'
        exit_time = t.get('exitTime', '?')[:16] if t.get('exitTime') else '?'
        print(f"  {i:3d} {t['symbol']:15s} {t['side']:5s} {t['entryPrice']:>10} {t.get('exitPrice',0):>10} {t['netPnl']:>+10.2f} {t.get('maxUnrealizedLoss',0):>+10.2f} {t.get('maxUnrealizedProfit',0):>+10.2f} {t['exitType']:12s} {entry_time:20s} {exit_time:20s}")
    
    # Summary
    wins = sum(1 for t in trades if t['netPnl'] > 0)
    total_win = sum(t['netPnl'] for t in trades if t['netPnl'] > 0)
    total_loss = sum(t['netPnl'] for t in trades if t['netPnl'] <= 0)
    avg_max_loss = sum(t.get('maxUnrealizedLoss', 0) for t in trades) / len(trades) if trades else 0
    avg_max_profit = sum(t.get('maxUnrealizedProfit', 0) for t in trades) / len(trades) if trades else 0
    worst_max_loss = min(t.get('maxUnrealizedLoss', 0) for t in trades) if trades else 0
    best_max_profit = max(t.get('maxUnrealizedProfit', 0) for t in trades) if trades else 0
    
    print(f"\n  SUMMARY: {wins}W/{len(trades)-wins}L | Net: Rs {total_win+total_loss:+,.2f} | Win: Rs {total_win:,.2f} | Loss: Rs {total_loss:,.2f}")
    print(f"  INTRA-TRADE: Avg MaxLoss: Rs {avg_max_loss:+,.2f} | Avg MaxProfit: Rs {avg_max_profit:+,.2f}")
    print(f"  WORST DRAWDOWN IN TRADE: Rs {worst_max_loss:+,.2f} | BEST RUN: Rs {best_max_profit:+,.2f}")
