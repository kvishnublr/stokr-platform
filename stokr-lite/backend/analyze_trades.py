import json, sys
from datetime import datetime, timedelta

data = json.load(sys.stdin)
positions = data['positions']

# Filter only real trades (not legacy ₹0 P&L junk)
real = [p for p in positions if (p.get('pnl') or 0) != 0 or p['status'] == 'OPEN']

# Calculate stats
profits = [p['pnl'] for p in real if p['status'] in ('CLOSED','EXITED') and (p.get('pnl') or 0) > 0]
losses = [p['pnl'] for p in real if p['status'] in ('CLOSED','EXITED') and (p.get('pnl') or 0) < 0]
opens = [p['pnl'] for p in real if p['status'] == 'OPEN']

print(f"=== TRADE ANALYSIS ===")
print(f"Total real trades: {len(real)}")
print(f"Profitable: {len(profits)}, Total profit: +₹{sum(profits):,.0f}")
print(f"Losing: {len(losses)}, Total loss: ₹{sum(losses):,.0f}")
print(f"Open (live): {len(opens)}, Current P&L: ₹{sum(opens):,.0f}")
print()

avg_profit = sum(profits)/len(profits) if profits else 0
avg_loss = sum(losses)/len(losses) if losses else 0
win_rate = len(profits)/(len(profits)+len(losses))*100 if (len(profits)+len(losses)) > 0 else 0

print(f"=== PER-TRADE STATS ===")
print(f"Avg profit per winning trade: +₹{avg_profit:,.0f}")
print(f"Avg loss per losing trade: ₹{avg_loss:,.0f}")
print(f"Win rate: {win_rate:.0f}%")
print(f"Profit factor: {abs(sum(profits)/sum(losses)) if losses else 'N/A'}:1")
print()

# Duration analysis
durations = []
for p in real:
    if p.get('enteredAt') and p.get('exitedAt'):
        try:
            e = datetime.fromisoformat(p['enteredAt'].replace('Z',''))
            x = datetime.fromisoformat(p['exitedAt'].replace('Z',''))
            d = (x - e).total_seconds() / 60
            if d > 0:
                durations.append(d)
        except:
            pass

if durations:
    avg_dur = sum(durations)/len(durations)
    max_dur = max(durations)
    min_dur = min(durations)
    print(f"=== DURATION ===")
    print(f"Avg hold time: {avg_dur:.0f} min ({avg_dur/60:.1f} hrs)")
    print(f"Min: {min_dur:.0f} min, Max: {max_dur:.0f} min ({max_dur/60:.1f} hrs)")
    print()

# Date range
dates = []
for p in real:
    if p.get('enteredAt'):
        try:
            dates.append(datetime.fromisoformat(p['enteredAt'].replace('Z','')))
        except:
            pass
if dates:
    span = (max(dates) - min(dates)).days + 1
    trades_per_day = len(real) / span if span > 0 else 0
    print(f"=== FREQUENCY ===")
    print(f"Date span: {min(dates).strftime('%d %b')} to {max(dates).strftime('%d %b')} ({span} days)")
    print(f"Trades per day: {trades_per_day:.1f}")
    print()

# Capital projection: ₹3L, 1 lot each
print(f"=== ₹3L CAPITAL PROJECTION (1 lot, 1 position at a time) ===")
print(f"Avg profit per trade: +₹{avg_profit:,.0f}")
print(f"Avg duration: {avg_dur:.0f} min ({avg_dur/60:.1f} hrs)")
print()

# Trades per day based on hold time
hrs_per_trade = avg_dur / 60
market_hrs = 6.25  # 9:15 to 3:30
trades_per_day_1set = market_hrs / hrs_per_trade if hrs_per_trade > 0 else 0
print(f"With 1 position at a time:")
print(f"  Trades/day: ~{trades_per_day_1set:.1f} (based on avg hold time)")
print(f"  Daily profit: ₹{avg_profit * trades_per_day_1set:,.0f}")
print(f"  Weekly (5 days): ₹{avg_profit * trades_per_day_1set * 5:,.0f}")
print(f"  Monthly (22 days): ₹{avg_profit * trades_per_day_1set * 22:,.0f}")
print()

# Based on actual observed frequency
print(f"=== BASED ON ACTUAL DATA ({trades_per_day:.1f} trades/day) ===")
daily_pnl = sum(profits)/(span) if span > 0 else 0
print(f"Avg daily profit (from real data): ₹{daily_pnl:,.0f}")
print(f"Weekly (5 days): ₹{daily_pnl * 5:,.0f}")
print(f"Monthly (22 days): ₹{daily_pnl * 22:,.0f}")
print()

# Capital required per trade
print(f"=== CAPITAL UTILIZATION ===")
avg_trade_value = 0
for p in real:
    if p.get('ceEntryPrice') and p.get('peEntryPrice') and p.get('lotSize'):
        val = (p['ceEntryPrice'] + p['peEntryPrice']) * p['lotSize']
        avg_trade_value += val
avg_trade_value = avg_trade_value / len([p for p in real if p.get('ceEntryPrice')]) if real else 0
print(f"Avg capital locked per trade: ₹{avg_trade_value:,.0f}")
print(f"With ₹3L, can take: {300000/avg_trade_value:.0f} lots simultaneously" if avg_trade_value > 0 else "")
