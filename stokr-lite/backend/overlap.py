import json, sys
d = json.load(open(sys.argv[1]))
trades = d.get('trades', [])
from collections import defaultdict
by_date = defaultdict(list)
for t in trades:
    entry_date = str(t.get('entryTime', ''))[:10]
    by_date[entry_date].append(t.get('symbol', '?'))

overlap_days = {d: syms for d, syms in by_date.items() if len(syms) > 1}
total_overlap = sum(len(s) - 1 for s in overlap_days.values())
print("Total trades:", len(trades))
print("Unique entry days:", len(by_date))
print("Days with overlap (2+ stocks same day):", len(overlap_days))
print("Overlap trades (should be 1 per day max):", total_overlap)
print()
for d in sorted(overlap_days.keys())[:10]:
    syms = overlap_days[d]
    print("  " + d + ": " + str(len(syms)) + " stocks: " + ", ".join(syms))

# Capital deployed per day
print()
print("Max stocks entered on single day:", max(len(s) for s in by_date.values()))
total_capital_needed = sum(len(s) for s in by_date.values()) * 100000
print("Total capital needed if all traded same day:", total_capital_needed)
