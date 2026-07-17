import json, sys
f = sys.argv[1] if len(sys.argv) > 1 else '/tmp/ob_fresh.json'
d = json.load(open(f))
trades = d.get('trades', [])
from collections import defaultdict
bd = defaultdict(list)
for t in trades:
    bd[str(t.get('entryTime',''))[:10]].append(t.get('symbol','?'))
print('trades:', len(trades))
print('days:', len(bd))
mx = max(len(s) for s in bd.values()) if bd else 0
print('max_per_day:', mx)
ov = {dt: s for dt, s in bd.items() if len(s) > 1}
print('overlap_days:', len(ov))
for dt in sorted(ov.keys())[:5]:
    print('  ' + dt + ': ' + str(len(ov[dt])) + ' stocks: ' + ', '.join(ov[dt]))
