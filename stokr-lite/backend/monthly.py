import json, sys
d = json.load(open(sys.argv[1]))
trades = d.get('trades', [])
from collections import defaultdict
monthly = defaultdict(lambda: {'trades': 0, 'wins': 0, 'pnl': 0})
for t in trades:
    entry = str(t.get('entryTime', ''))[:7]
    monthly[entry]['trades'] += 1
    monthly[entry]['pnl'] += t.get('pnl', 0) - t.get('brokerage', 0)
    if t.get('pnl', 0) > t.get('brokerage', 0):
        monthly[entry]['wins'] += 1

header = f"{'Month':>10s} {'Trades':>7s} {'Win%':>6s} {'Net PnL':>12s}"
print(header)
for m in sorted(monthly.keys()):
    v = monthly[m]
    wr = 100.0 * v['wins'] / v['trades'] if v['trades'] > 0 else 0
    print(f"{m:>10s} {v['trades']:>7d} {wr:>5.1f}% {v['pnl']:>11.0f}")
total_trades = sum(v['trades'] for v in monthly.values())
total_pnl = sum(v['pnl'] for v in monthly.values())
total_wins = sum(v['wins'] for v in monthly.values())
n = len(monthly)
print(f"{'TOTAL':>10s} {total_trades:>7d} {100.0*total_wins/total_trades:>5.1f}% {total_pnl:>11.0f}")
print(f"{'AVG/MO':>10s} {total_trades//n:>7d}          {total_pnl/n:>11.0f}")
