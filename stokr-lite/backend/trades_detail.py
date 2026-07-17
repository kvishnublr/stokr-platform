import json, sys
f = sys.argv[1]
d = json.load(open(f))
trades = d.get('trades', [])
for t in trades:
    et = str(t.get('entryTime', ''))[:10]
    if '2026-03' in et or '2026-04' in et:
        print(et, t.get('symbol'), 'qty=' + str(t.get('qty')), 'entry=' + str(t.get('entryPrice')), 'exit=' + str(t.get('exitPrice')), 'pnl=' + str(t.get('pnl')), 'exitType=' + str(t.get('exitType')), 'capital=' + str(t.get('tradeCapital')))
