import json

with open('/tmp/ob_result.json') as f:
    old = json.load(f)
with open('/tmp/ob_v2.json') as f:
    new = json.load(f)

print("=" * 90)
print("BEFORE vs AFTER TWEAKS COMPARISON")
print("=" * 90)

print(f"\n{'Metric':<30} {'BEFORE':>15} {'AFTER':>15} {'Change':>15}")
print("-" * 75)

metrics = [
    ('Total Trades', 'totalTrades', 'd'),
    ('Win Count', 'winCount', 'd'),
    ('Loss Count', 'lossCount', 'd'),
    ('Win Rate', 'winRate', '.1f'),
    ('Gross PnL', 'totalPnL', ',.2f'),
    ('Brokerage', 'totalBrokerage', ',.2f'),
    ('Net PnL', 'netPnL', ',.2f'),
    ('Avg PnL/Trade', 'avgPnL', ',.2f'),
    ('Profit Factor', 'profitFactor', '.2f'),
    ('Max Drawdown', 'maxDrawdown', ',.2f'),
    ('Max Profit Day', 'maxProfitDay', ',.2f'),
    ('Max Loss Day', 'maxLossDay', ',.2f'),
]

for name, key, fmt in metrics:
    ov = old.get(key, 0)
    nv = new.get(key, 0)
    diff = nv - ov
    sign = '+' if diff > 0 else ''
    if fmt == 'd':
        print(f"{name:<30} {ov:>15d} {nv:>15d} {sign}{diff:>14d}")
    elif fmt == '.1f':
        print(f"{name:<30} {ov:>14.1f}% {nv:>14.1f}% {sign}{diff:>13.1f}%")
    else:
        print(f"{name:<30} ₹{ov:>13,.2f} ₹{nv:>13,.2f} ₹{sign}{diff:>12,.2f}")

# Exit type comparison
print(f"\n{'EXIT TYPE COMPARISON':^75}")
print("-" * 75)
print(f"{'Type':<18} {'OLD Count':>10} {'OLD PnL':>12} {'NEW Count':>10} {'NEW PnL':>12} {'Diff':>10}")
print("-" * 75)

old_exit = {}
for t in old['trades']:
    et = t['exitType']
    if et not in old_exit: old_exit[et] = {'count': 0, 'pnl': 0}
    old_exit[et]['count'] += 1
    old_exit[et]['pnl'] += t['netPnl']

new_exit = {}
for t in new['trades']:
    et = t['exitType']
    if et not in new_exit: new_exit[et] = {'count': 0, 'pnl': 0}
    new_exit[et]['count'] += 1
    new_exit[et]['pnl'] += t['netPnl']

all_types = sorted(set(list(old_exit.keys()) + list(new_exit.keys())))
for et in all_types:
    oc = old_exit.get(et, {'count': 0, 'pnl': 0})
    nc = new_exit.get(et, {'count': 0, 'pnl': 0})
    diff = nc['pnl'] - oc['pnl']
    sign = '+' if diff > 0 else ''
    print(f"{et:<18} {oc['count']:>10} ₹{oc['pnl']:>+10,.2f} {nc['count']:>10} ₹{nc['pnl']:>+10,.2f} ₹{sign}{diff:>8,.2f}")

# Monthly comparison
print(f"\n{'MONTHLY COMPARISON':^75}")
print("-" * 75)
print(f"{'Month':<10} {'OLD':>12} {'NEW':>12} {'Diff':>12}")
print("-" * 75)

old_monthly = {}
for t in old['trades']:
    m = t['entryTime'][:7]
    old_monthly[m] = old_monthly.get(m, 0) + t['netPnl']

new_monthly = {}
for t in new['trades']:
    m = t['entryTime'][:7]
    new_monthly[m] = new_monthly.get(m, 0) + t['netPnl']

all_months = sorted(set(list(old_monthly.keys()) + list(new_monthly.keys())))
for m in all_months:
    ov = old_monthly.get(m, 0)
    nv = new_monthly.get(m, 0)
    diff = nv - ov
    sign = '+' if diff > 0 else ''
    print(f"{m:<10} ₹{ov:>+10,.2f} ₹{nv:>+10,.2f} ₹{sign}{diff:>10,.2f}")

# Top trades comparison
print(f"\n{'TOP 5 WINNERS (NEW)':^75}")
print("-" * 75)
sorted_new = sorted(new['trades'], key=lambda x: x['netPnl'], reverse=True)
for i, t in enumerate(sorted_new[:5], 1):
    print(f"  {i}. {t['symbol']:<14} {t['entryTime'][:10]} ₹{t['netPnl']:>+10,.2f} ({t['exitType']})")

print(f"\n{'TOP 5 LOSERS (NEW)':^75}")
print("-" * 75)
for i, t in enumerate(sorted_new[-5:][::-1], 1):
    print(f"  {i}. {t['symbol']:<14} {t['entryTime'][:10]} ₹{t['netPnl']:>+10,.2f} ({t['exitType']})")

# Hold duration comparison
print(f"\n{'HOLD DURATION COMPARISON':^75}")
print("-" * 75)
from datetime import datetime
for label, dataset in [('OLD', old), ('NEW', new)]:
    holds = {}
    for t in dataset['trades']:
        e = datetime.strptime(t['entryTime'][:10], '%Y-%m-%d')
        x = datetime.strptime(t['exitTime'][:10], '%Y-%m-%d')
        d = (x - e).days
        if d not in holds: holds[d] = 0
        holds[d] += 1
    parts = [f"{d}d:{c}" for d, c in sorted(holds.items())]
    print(f"  {label}: {', '.join(parts)}")

# Monthly return on capital
print(f"\n{'MONTHLY RETURN ON ₹1L CAPITAL':^75}")
print("-" * 75)
old_monthly_avg = old['netPnL'] / 12
new_monthly_avg = new['netPnL'] / 12
print(f"  BEFORE: ₹{old_monthly_avg:,.2f}/month ({old_monthly_avg/old['capitalPerTrade']*100:.1f}%)")
print(f"  AFTER:  ₹{new_monthly_avg:,.2f}/month ({new_monthly_avg/new['capitalPerTrade']*100:.1f}%)")
print(f"  IMPROVEMENT: ₹{new_monthly_avg - old_monthly_avg:,.2f}/month")
