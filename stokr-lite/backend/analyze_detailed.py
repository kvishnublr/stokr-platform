import json, sys
from datetime import datetime

data = json.load(sys.stdin)
positions = data['positions']

# Remove legacy junk
real = [p for p in positions if not p.get('errorMessage','').startswith('INVALID_LEGACY') 
        and not p.get('errorMessage','').startswith('PAPER CALENDAR')
        and not p.get('errorMessage','').startswith('EXIT test')
        and not p.get('errorMessage','').startswith('EXIT verify')]

real.sort(key=lambda p: p['enteredAt'] or '')

print("=" * 120)
print("EVERY SINGLE TRADE - DETAILED TIMELINE")
print("=" * 120)

for p in real:
    entered = p['enteredAt'][:19] if p.get('enteredAt') else 'N/A'
    exited = p['exitedAt'][:19] if p.get('exitedAt') else 'STILL OPEN'
    
    ce_entry = p.get('ceEntryPrice', '--')
    pe_entry = p.get('peEntryPrice', '--')
    fut_entry = p.get('futEntryPrice', '--')
    ce_exit = p.get('ceExitPrice', '--')
    pe_exit = p.get('peExitPrice', '--')
    fut_exit = p.get('futExitPrice', '--')
    pnl = p.get('pnl', 0)
    target = p.get('targetEdge', 0)
    status = p['status']
    
    # Calculate duration
    dur_str = '--'
    if p.get('enteredAt') and p.get('exitedAt'):
        try:
            e = datetime.fromisoformat(p['enteredAt'].replace('Z',''))
            x = datetime.fromisoformat(p['exitedAt'].replace('Z',''))
            mins = (x - e).total_seconds() / 60
            if mins > 60:
                dur_str = f"{mins/60:.1f} hrs ({mins:.0f} min)"
            else:
                dur_str = f"{mins:.0f} min"
        except:
            pass
    
    pnl_str = f"+₹{pnl:,.0f}" if pnl > 0 else f"₹{pnl:,.0f}" if pnl < 0 else "₹0"
    
    print(f"\n#{p['id']} | {p['underlying']} {p['strike']} | {p['action']}")
    print(f"  Status: {status} | Duration: {dur_str} | Target Edge: ₹{target} | P&L: {pnl_str}")
    print(f"  ENTRY: {entered}")
    print(f"    CE @ ₹{ce_entry} | PE @ ₹{pe_entry} | FUT @ ₹{fut_entry}")
    print(f"  EXIT:  {exited}")
    print(f"    CE @ ₹{ce_exit} | PE @ ₹{pe_exit} | FUT @ ₹{fut_exit}")
    if p.get('errorMessage'):
        print(f"  Note: {p['errorMessage'][:100]}")

# Check simultaneous trades
print("\n\n" + "=" * 120)
print("SIMULTANEOUS TRADE CHECK")
print("=" * 120)

# Build active intervals
intervals = []
for p in real:
    if p.get('enteredAt') and p.get('exitedAt'):
        try:
            e = datetime.fromisoformat(p['enteredAt'].replace('Z',''))
            x = datetime.fromisoformat(p['exitedAt'].replace('Z',''))
            intervals.append((e, x, p['id'], p['underlying'], p['strike']))
        except:
            pass

intervals.sort()

max_overlap = 0
max_overlap_ids = []
for i in range(len(intervals)):
    current_active = [intervals[i]]
    for j in range(len(intervals)):
        if i == j:
            continue
        # Check overlap
        if intervals[j][0] <= intervals[i][1] and intervals[j][1] >= intervals[i][0]:
            current_active.append(intervals[j])
    if len(current_active) > max_overlap:
        max_overlap = len(current_active)
        max_overlap_ids = current_active

print(f"\nMax simultaneous positions: {max_overlap}")
if max_overlap > 1:
    print("Overlapping positions:")
    for act in max_overlap_ids:
        print(f"  #{act[2]} {act[3]} {act[4]} | {act[0].strftime('%d %b %H:%M')} to {act[1].strftime('%d %b %H:%M')}")
else:
    print("ONLY 1 POSITION AT A TIME - No overlapping trades found!")

# Show daily timeline
print("\n\n" + "=" * 120)
print("DAILY TIMELINE (what happened each day)")
print("=" * 120)

from collections import defaultdict
by_date = defaultdict(list)
for p in real:
    if p.get('enteredAt'):
        day = p['enteredAt'][:10]
        by_date[day].append(p)

for day in sorted(by_date.keys()):
    trades = by_date[day]
    print(f"\n--- {day} ({len(trades)} trades) ---")
    for t in trades:
        entered = t['enteredAt'][11:19] if t.get('enteredAt') else '?'
        exited = t['exitedAt'][11:19] if t.get('exitedAt') else 'OPEN'
        pnl = t.get('pnl', 0)
        pnl_str = f"+₹{pnl}" if pnl > 0 else f"₹{pnl}" if pnl < 0 else "₹0"
        print(f"  {entered} -> {exited} | {t['underlying']} {t['strike']} | {t['status']} | {pnl_str}")
