import json, sys
from datetime import datetime

data = json.load(sys.stdin)
positions = data['positions']

# Only real profitable trades
real = [p for p in positions if (p.get('pnl') or 0) > 0]

print(f"=== CLEAN DATA: {len(real)} profitable trades ===\n")

# Per trade stats
total_pnl = sum(p['pnl'] for p in real)
avg_pnl = total_pnl / len(real) if real else 0
print(f"Total P&L: ₹{total_pnl:,.0f}")
print(f"Avg profit/trade: ₹{avg_pnl:,.0f}")

# Duration analysis
durations_min = []
for p in real:
    if p.get('enteredAt') and p.get('exitedAt'):
        try:
            e = datetime.fromisoformat(p['enteredAt'].replace('Z',''))
            x = datetime.fromisoformat(p['exitedAt'].replace('Z',''))
            m = (x - e).total_seconds() / 60
            if m > 0:
                durations_min.append(m)
        except:
            pass

avg_dur = sum(durations_min)/len(durations_min) if durations_min else 0
print(f"Avg hold: {avg_dur:.0f} min ({avg_dur/60:.1f} hrs)")

# Trades per day
dates = set()
for p in real:
    if p.get('enteredAt'):
        dates.add(p['enteredAt'][:10])
days = len(dates)
trades_per_day = len(real) / days if days else 0
print(f"Trades/day: {trades_per_day:.1f} (over {days} days)")

# Market hours = 6.25 hrs = 375 min
market_min = 375
trades_per_market_day = market_min / avg_dur if avg_dur > 0 else 0
print(f"Max trades/day (based on hold time): {trades_per_market_day:.1f}")

print(f"\n=== 1 SET AT A TIME - 1 WEEK PROJECTION ===")
print(f"Scenario 1: Based on actual data ({trades_per_day:.1f} trades/day)")
daily_actual = avg_pnl * trades_per_day
print(f"  Daily: ₹{daily_actual:,.0f}")
print(f"  Weekly (5 days): ₹{daily_actual * 5:,.0f}")
print(f"  Monthly (22 days): ₹{daily_actual * 22:,.0f}")

print(f"\nScenario 2: Aggressive (max {trades_per_market_day:.1f} trades/day)")
daily_max = avg_pnl * trades_per_market_day
print(f"  Daily: ₹{daily_max:,.0f}")
print(f"  Weekly (5 days): ₹{daily_max * 5:,.0f}")
print(f"  Monthly (22 days): ₹{daily_max * 22:,.0f}")

print(f"\nScenario 3: Conservative (1 trade/day, avg hold)")
daily_conservative = avg_pnl * 1
print(f"  Daily: ₹{daily_conservative:,.0f}")
print(f"  Weekly (5 days): ₹{daily_conservative * 5:,.0f}")
print(f"  Monthly (22 days): ₹{daily_conservative * 22:,.0f}")

# Capital required
print(f"\n=== CAPITAL ===")
capitals = []
for p in real:
    if p.get('ceEntryPrice') and p.get('peEntryPrice') and p.get('lotSize'):
        cap = (p['ceEntryPrice'] + p['peEntryPrice']) * p['lotSize']
        capitals.append(cap)
avg_cap = sum(capitals)/len(capitals) if capitals else 0
print(f"Avg capital per set: ₹{avg_cap:,.0f}")
print(f"With ₹3L: can run 1 set easily (₹{avg_cap:,.0f} needed)")
print(f"ROI/week: {(daily_actual * 5 / avg_cap * 100):.1f}%")
print(f"ROI/month: {(daily_actual * 22 / avg_cap * 100):.1f}%")
