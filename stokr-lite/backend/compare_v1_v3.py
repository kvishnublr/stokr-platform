import json

with open('/tmp/ob_result.json') as f:
    old = json.load(f)
with open('/tmp/ob_v3.json') as f:
    new = json.load(f)

print("=" * 80)
print("v1 (ORIGINAL) vs v3 (wider target + wider trail)")
print("v3 params: SL=3%, Target=2%, Trail=0.5%/0.25%, MaxHold=7d")
print("=" * 80)

print(f"\n{'Metric':<30} {'v1 ORIGINAL':>15} {'v3 TWEAKED':>15} {'Change':>15}")
print("-" * 75)

for name, key, fmt in [
    ('Trades', 'totalTrades', 'd'),
    ('Wins', 'winCount', 'd'),
    ('Losses', 'lossCount', 'd'),
    ('Win Rate', 'winRate', '.1f'),
    ('Gross PnL', 'totalPnL', ',.2f'),
    ('Brokerage', 'totalBrokerage', ',.2f'),
    ('Net PnL', 'netPnL', ',.2f'),
    ('Avg PnL/Trade', 'avgPnL', ',.2f'),
    ('Profit Factor', 'profitFactor', '.2f'),
    ('Max Drawdown', 'maxDrawdown', ',.2f'),
]:
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

print(f"\nEXIT TYPE COMPARISON:")
print(f"{'Type':<18} {'v1':>8} {'v3':>8} {'v1 PnL':>12} {'v3 PnL':>12} {'Diff':>10}")
print("-" * 68)

old_exit = {}
for t in old['trades']:
    et = t['exitType']
    if et not in old_exit: old_exit[et] = {'c': 0, 'p': 0}
    old_exit[et]['c'] += 1
    old_exit[et]['p'] += t['netPnl']

new_exit = {}
for t in new['trades']:
    et = t['exitType']
    if et not in new_exit: new_exit[et] = {'c': 0, 'p': 0}
    new_exit[et]['c'] += 1
    new_exit[et]['p'] += t['netPnl']

for et in sorted(set(list(old_exit.keys()) + list(new_exit.keys()))):
    o = old_exit.get(et, {'c': 0, 'p': 0})
    n = new_exit.get(et, {'c': 0, 'p': 0})
    diff = n['p'] - o['p']
    sign = '+' if diff > 0 else ''
    print(f"{et:<18} {o['c']:>8} {n['c']:>8} ₹{o['p']:>+10,.2f} ₹{n['p']:>+10,.2f} ₹{sign}{diff:>8,.2f}")

old_avg = old['netPnL'] / 12
new_avg = new['netPnL'] / 12
print(f"\nMonthly return: v1=₹{old_avg:,.0f}/mo ({old_avg/old['capitalPerTrade']*100:.1f}%) → v3=₹{new_avg:,.0f}/mo ({new_avg/new['capitalPerTrade']*100:.1f}%)")
print(f"Delta: ₹{new_avg - old_avg:,.0f}/mo")
