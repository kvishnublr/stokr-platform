import psycopg2
from collections import defaultdict

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='`$POSTGRES_PASSWORD')
cur = conn.cursor()

cur.execute("""
    SELECT symbol, count(*) as cnt FROM candle_data 
    WHERE timeframe = '1min' 
    GROUP BY symbol HAVING count(*) > 5000
    ORDER BY cnt DESC LIMIT 30
""")
symbols = [r[0] for r in cur.fetchall()]

cur.execute("""
    SELECT symbol, timestamp, open, high, low, close, volume
    FROM candle_data WHERE timeframe = '1min' AND symbol IN %s
    ORDER BY symbol, timestamp
""", (tuple(symbols),))

data = defaultdict(list)
for row in cur.fetchall():
    sym, ts, o, h, l, c, v = row
    data[sym].append({'ts': ts, 'open': float(o), 'high': float(h), 'low': float(l), 'close': float(c), 'volume': int(v or 0)})

def get_day_candles(candles):
    days = defaultdict(list)
    for c in candles: days[c['ts'].date()].append(c)
    return days

BROKERAGE = 80
CAPITAL = 100000

print("=" * 95)
print("MICRO V-REVERSAL PARAMETER OPTIMIZATION")
print("=" * 95)

# Test different combinations of: drop threshold, SL, target, max hold
configs = [
    # (drop_pct, sl_pct, tgt_pct, max_hold, label)
    (-0.3, 0.003, 0.003, 10, "drop=-0.3% SL=0.3% Tgt=0.3%"),
    (-0.3, 0.005, 0.005, 10, "drop=-0.3% SL=0.5% Tgt=0.5%"),
    (-0.3, 0.005, 0.008, 10, "drop=-0.3% SL=0.5% Tgt=0.8%"),
    (-0.3, 0.005, 0.010, 10, "drop=-0.3% SL=0.5% Tgt=1.0%"),
    (-0.3, 0.005, 0.015, 15, "drop=-0.3% SL=0.5% Tgt=1.5%"),
    (-0.5, 0.005, 0.005, 10, "drop=-0.5% SL=0.5% Tgt=0.5%"),
    (-0.5, 0.005, 0.008, 10, "drop=-0.5% SL=0.5% Tgt=0.8%"),
    (-0.5, 0.005, 0.010, 10, "drop=-0.5% SL=0.5% Tgt=1.0%"),
    (-0.5, 0.005, 0.015, 15, "drop=-0.5% SL=0.5% Tgt=1.5%"),
    (-0.5, 0.008, 0.010, 10, "drop=-0.5% SL=0.8% Tgt=1.0%"),
    (-0.5, 0.008, 0.015, 15, "drop=-0.5% SL=0.8% Tgt=1.5%"),
    (-0.5, 0.010, 0.010, 10, "drop=-0.5% SL=1.0% Tgt=1.0%"),
    (-0.5, 0.010, 0.015, 15, "drop=-0.5% SL=1.0% Tgt=1.5%"),
    (-0.7, 0.005, 0.008, 10, "drop=-0.7% SL=0.5% Tgt=0.8%"),
    (-0.7, 0.005, 0.010, 10, "drop=-0.7% SL=0.5% Tgt=1.0%"),
    (-0.7, 0.008, 0.010, 10, "drop=-0.7% SL=0.8% Tgt=1.0%"),
    (-0.7, 0.008, 0.015, 15, "drop=-0.7% SL=0.8% Tgt=1.5%"),
    (-0.7, 0.010, 0.015, 15, "drop=-0.7% SL=1.0% Tgt=1.5%"),
    (-0.7, 0.010, 0.020, 20, "drop=-0.7% SL=1.0% Tgt=2.0%"),
    (-1.0, 0.008, 0.010, 10, "drop=-1.0% SL=0.8% Tgt=1.0%"),
    (-1.0, 0.008, 0.015, 15, "drop=-1.0% SL=0.8% Tgt=1.5%"),
    (-1.0, 0.010, 0.015, 15, "drop=-1.0% SL=1.0% Tgt=1.5%"),
    (-1.0, 0.010, 0.020, 20, "drop=-1.0% SL=1.0% Tgt=2.0%"),
    (-1.0, 0.015, 0.020, 20, "drop=-1.0% SL=1.5% Tgt=2.0%"),
]

print(f"\n{'Config':<38} {'T':>5} {'W':>5} {'WR%':>6} {'Net':>10} {'PF':>6} {'AvgW':>8} {'AvgL':>8} {'$/mo':>8}")
print("-" * 95)

best = None
for drop_pct, sl_pct, tgt_pct, max_hold, label in configs:
    trades = []
    for sym, candles in data.items():
        days = get_day_candles(candles)
        for day, dc in sorted(days.items()):
            if len(dc) < 60: continue
            entered = False
            for i in range(10, len(dc) - 2):
                if entered: break
                drop_start = dc[i-2]['open']
                drop_end = dc[i]['close']
                if drop_start <= 0: continue
                drop = (drop_end - drop_start) / drop_start * 100
                
                if drop <= drop_pct and dc[i+1]['close'] > dc[i]['close']:
                    entry = dc[i+1]['close']
                    sl = entry * (1 - sl_pct)
                    target = entry * (1 + tgt_pct)
                    for j in range(i+2, min(i+max_hold, len(dc))):
                        if dc[j]['low'] <= sl:
                            trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                        elif dc[j]['high'] >= target:
                            trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                entered = False
    
    if not trades: continue
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    aw = sum(t['pnl'] for t in wins)/len(wins) if wins else 0
    al = sum(t['pnl'] for t in losses)/len(losses) if losses else 0
    monthly = total / 1  # ~1 month of data
    
    marker = " â˜…" if (best is None or total > best['total']) and len(trades) >= 10 else ""
    if total > (best['total'] if best else 0) and len(trades) >= 10:
        best = {'total': total, 'wr': wr, 'pf': pf, 'label': label, 'trades': len(trades)}
    
    print(f"  {label:<36} {len(trades):>5} {len(wins):>5} {wr:>5.1f}% {total:>+9,.0f} {pf:>5.2f} {aw:>7,.0f} {al:>7,.0f} {monthly:>+7,.0f}{marker}")

if best:
    print(f"\nâ˜… BEST: {best['label']}")
    print(f"  Trades: {best['trades']} | WR: {best['wr']:.1f}% | PF: {best['pf']:.2f} | Net: â‚¹{best['total']:,.0f}")
    print(f"  Monthly (1 month data): â‚¹{best['total']:,.0f}")

conn.close()

