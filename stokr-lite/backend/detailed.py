import json, sys
f = sys.argv[1] if len(sys.argv) > 1 else '/tmp/ob_fresh2.json'
d = json.load(open(f))
print("=== SUMMARY ===")
print("trades:", d.get('totalTrades'))
print("winRate:", d.get('winRate'))
print("profitFactor:", d.get('profitFactor'))
print("maxDrawdown:", d.get('maxDrawdown'))
print("totalNetPnl:", d.get('totalNetPnl'))
print("totalBrokerage:", d.get('totalBrokerage'))

trades = d.get('trades', [])
from collections import defaultdict

gross = 0
net = 0
wins = 0
losses = 0
for t in trades:
    p = t.get('pnl', 0)
    b = t.get('brokerage', 0)
    gross += p
    net += (p - b)
    if p > 0: wins += 1
    elif p < 0: losses += 1

print("\n=== DETAILED ===")
print("Gross PnL:", round(gross, 0))
print("Total Brokerage:", round(sum(t.get('brokerage', 0) for t in trades), 0))
print("Net PnL:", round(net, 0))
print("Wins:", wins, "Losses:", losses)
print("Avg Win:", round(gross / wins, 0) if wins else 0)
print("Avg Loss:", round(gross / losses, 0) if losses else 0)

# Per-entry capital impact
print("\n=== CAPITAL IMPACT ===")
print("On 1L capital, 6 months: +" + str(round(net)) + " = +" + str(round(net/1000, 1)) + "k")
print("Monthly avg: +" + str(round(net/6, 0)))
print("Monthly ROI: " + str(round(net/6/100000*100, 2)) + "%")

monthly = defaultdict(lambda: {'count': 0, 'gross': 0, 'net': 0, 'wins': 0, 'brokerage': 0})
for t in trades:
    entry_date = str(t.get('entryTime', ''))[:7]
    p = t.get('pnl', 0)
    b = t.get('brokerage', 0)
    monthly[entry_date]['count'] += 1
    monthly[entry_date]['gross'] += p
    monthly[entry_date]['net'] += (p - b)
    monthly[entry_date]['brokerage'] += b
    if p > 0: monthly[entry_date]['wins'] += 1

print("\n=== MONTHLY ===")
for m in sorted(monthly.keys()):
    v = monthly[m]
    wr = round(v['wins'] / v['count'] * 100, 1) if v['count'] > 0 else 0
    print("  " + m + ": " + str(v['count']) + " trades, " + str(wr) + "% WR, Gross=" + str(round(v['gross'], 0)) + ", Brok=" + str(round(v['brokerage'], 0)) + ", Net=" + str(round(v['net'], 0)))

# Exit type breakdown
exits = defaultdict(int)
for t in trades:
    exits[t.get('exitType', '?')] += 1
print("\n=== EXIT TYPES ===")
for k, v in sorted(exits.items(), key=lambda x: -x[1]):
    print("  " + k + ": " + str(v))
