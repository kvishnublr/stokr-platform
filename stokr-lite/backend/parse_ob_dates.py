import json
from datetime import datetime

with open('/tmp/ob_result.json') as f:
    data = json.load(f)

trades = data['trades']

print(f"{'='*105}")
print(f"OVERSOLD BOUNCE — ENTRY/EXIT DATES (Daily Strategy)")
print(f"Entry: Signal evaluated ~15:15 IST, order next day open | Exit: SL/target during market hours")
print(f"{'='*105}")
print(f"{'#':>3} {'Symbol':<14} {'Entry Date':>12} {'Exit Date':>12} {'Hold':>5} {'PnL':>10} {'Exit Type':<18}")
print(f"{'-'*105}")

for i, t in enumerate(trades, 1):
    entry = t['entryTime'][:10]
    exit_ = t['exitTime'][:10]
    
    # Calculate hold days
    e = datetime.strptime(entry, '%Y-%m-%d')
    x = datetime.strptime(exit_, '%Y-%m-%d')
    hold = (x - e).days
    
    sign = '+' if t['netPnl'] >= 0 else ''
    print(f"{i:>3} {t['symbol']:<14} {entry:>12} {exit_:>12} {hold:>3}d {sign}{t['netPnl']:>9.2f} {t['exitType']:<18}")

print(f"{'-'*105}")
print(f"\nTIMING DETAILS:")
print(f"  Entry signal: Evaluated at 15:15 IST (EOD)")
print(f"  Entry order:  Next day market open ~09:15 IST")
print(f"  Exit order:   SL/target checked every minute 09:15-15:30 IST")
print(f"  Max hold:     3 days (MAX_HOLD_EXIT)")
print(f"  EOD exit:     15:15 IST forced exit (EOD_EXIT)")

# Show hold duration distribution
print(f"\n{'='*60}")
print(f"HOLD DURATION DISTRIBUTION")
print(f"{'='*60}")
holds = {}
for t in trades:
    e = datetime.strptime(t['entryTime'][:10], '%Y-%m-%d')
    x = datetime.strptime(t['exitTime'][:10], '%Y-%m-%d')
    d = (x - e).days
    if d not in holds:
        holds[d] = {'count': 0, 'pnl': 0}
    holds[d]['count'] += 1
    holds[d]['pnl'] += t['netPnl']

print(f"{'Days':>6} {'Trades':>8} {'Total PnL':>12} {'Avg PnL':>10}")
print(f"{'-'*38}")
for d in sorted(holds.keys()):
    h = holds[d]
    print(f"{d:>4}d {h['count']:>8} {h['pnl']:>+12.2f} {h['pnl']/h['count']:>+10.2f}")

# Show day-of-week distribution
print(f"\n{'='*60}")
print(f"DAY-OF-WEEK ENTRY DISTRIBUTION")
print(f"{'='*60}")
days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']
dow = {d: {'count': 0, 'pnl': 0, 'wins': 0} for d in days}
for t in trades:
    e = datetime.strptime(t['entryTime'][:10], '%Y-%m-%d')
    day_name = days[e.weekday()]
    dow[day_name]['count'] += 1
    dow[day_name]['pnl'] += t['netPnl']
    if t['netPnl'] > 0:
        dow[day_name]['wins'] += 1

print(f"{'Day':>6} {'Trades':>8} {'Wins':>6} {'Win%':>7} {'Total PnL':>12}")
print(f"{'-'*42}")
for d in days:
    if dow[d]['count'] > 0:
        wr = dow[d]['wins']/dow[d]['count']*100
        print(f"{d:>6} {dow[d]['count']:>8} {dow[d]['wins']:>6} {wr:>6.1f}% {dow[d]['pnl']:>+12.2f}")
