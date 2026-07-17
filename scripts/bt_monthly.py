#!/usr/bin/env python3
"""Print monthly breakdown for top strategies"""
import json

with open("/tmp/all_strategies_3month.json") as f:
    results = json.load(f)

# Print monthly breakdown for top profitable strategies
top = [r for r in results if not r.get("error") and r.get("trades", 0) > 0]
top.sort(key=lambda x: x.get("net_pnl", 0), reverse=True)

print("="*100)
print("MONTHLY BREAKDOWN — TOP STRATEGIES")
print("="*100)

for r in top[:10]:
    monthly = r.get("monthly", {})
    if not monthly:
        continue
    print(f"\n{r['short']} ({r['cat']}) — Total: {r['trades']} trades, P&L: ₹{r['net_pnl']:.0f}")
    print(f"  {'Month':12s} {'Trades':>6s} {'Wins':>5s} {'P&L':>10s} {'WR':>6s}")
    for month in sorted(monthly.keys()):
        m = monthly[month]
        wr = (m["wins"] / m["trades"] * 100) if m["trades"] > 0 else 0
        pnl_str = f"₹{m['pnl']:>8.0f}"
        print(f"  {month:12s} {m['trades']:6d} {m['wins']:5d} {pnl_str:>10s} {wr:5.1f}%")

# Print per-trade details for top 5
print("\n" + "="*100)
print("TRADE-LEVEL DETAILS — TOP 5 STRATEGIES")
print("="*100)

for r in top[:5]:
    print(f"\n{'='*80}")
    print(f"{r['short']} ({r['cat']}) — {r['trades']} trades, WR={r['win_rate']:.1f}%, P&L=₹{r['net_pnl']:.0f}, PF={r['pf']:.2f}, MaxDD=₹{r['max_dd']:.0f}")
    print(f"{'='*80}")
