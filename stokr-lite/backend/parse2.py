import json, sys
f = sys.argv[1] if len(sys.argv) > 1 else '/tmp/ob_fixed2.json'
d = json.load(open(f))
print("trades:", d.get('totalTrades'))
print("winRate:", d.get('winRate'))
print("netPnl:", d.get('totalNetPnl'))
print("pf:", d.get('profitFactor'))
print("maxDd:", d.get('maxDrawdown'))

trades = d.get('trades', [])
from collections import defaultdict
by_date = defaultdict(list)
for t in trades:
    entry_date = str(t.get('entryTime', ''))[:10]
    by_date[entry_date].append(t.get('symbol', '?'))

overlap_days = {dt: syms for dt, syms in by_date.items() if len(syms) > 1}
print("Total trades:", len(trades))
print("Unique entry days:", len(by_date))
print("Days with overlap:", len(overlap_days))
max_per_day = max(len(s) for s in by_date.values()) if by_date else 0
print("Max stocks on single day:", max_per_day)

monthly = defaultdict(lambda: {'count': 0, 'pnl': 0, 'wins': 0})
for t in trades:
    entry_date = str(t.get('entryTime', ''))[:7]
    pnl = t.get('netPnl', 0)
    monthly[entry_date]['count'] += 1
    monthly[entry_date]['pnl'] += pnl
    if pnl > 0:
        monthly[entry_date]['wins'] += 1

print("\nMonthly:")
for m in sorted(monthly.keys()):
    v = monthly[m]
    wr = round(v['wins'] / v['count'] * 100, 1) if v['count'] > 0 else 0
    print("  " + m + ": " + str(v['count']) + " trades, " + str(wr) + "% WR, PnL=" + str(round(v['pnl'], 0)))
