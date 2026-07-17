import json, sys
d = json.load(open(sys.argv[1]))
trades = d.get('trades', [])
print('Total:', len(trades), 'trades')
for t in trades[:10]:
    sym = t.get('symbol', '?')
    et = t.get('exitType', '?')
    pnl = t.get('pnl', 0)
    brk = t.get('brokerage', 0)
    ep = t.get('entryPrice', 0)
    xp = t.get('exitPrice', 0)
    print(f'  {sym:12s} {et:15s} pnl={pnl:>10.0f} entry={ep:>8.0f} exit={xp:>8.0f}')
from collections import Counter
exits = Counter(t.get('exitType', '?') for t in trades)
print('Exit types:', dict(exits))
print()
for t in trades[:3]:
    eT = str(t.get('entryTime', '?'))[:10]
    xT = str(t.get('exitTime', '?'))[:10]
    sym = t.get('symbol', '?')
    et = t.get('exitType', '?')
    print(f'  {sym} entry={eT} exit={xT} type={et}')
