import psycopg2
from collections import defaultdict

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='stokr2026')
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
print("V-REVERSAL: LONG + SHORT combined, best params")
print("=" * 95)

# Test the best config: drop=-1.0% SL=1.0% Tgt=1.5% (LONG only first)
# Then try SHORT side too

configs = [
    (-1.0, 0.010, 0.015, 15, "LONG: drop=-1.0% SL=1.0% Tgt=1.5%"),
    (-0.7, 0.010, 0.015, 15, "LONG: drop=-0.7% SL=1.0% Tgt=1.5%"),
    (-0.5, 0.010, 0.015, 15, "LONG: drop=-0.5% SL=1.0% Tgt=1.5%"),
]

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
                            trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE, 'type': 'SL'}); entered=True; break
                        elif dc[j]['high'] >= target:
                            trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE, 'type': 'WIN'}); entered=True; break
                entered = False
    
    if not trades: continue
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    print(f"\n{label}")
    print(f"  Trades: {len(trades)} | Wins: {len(wins)} | WR: {wr:.1f}% | Net: ₹{total:,.0f} | PF: {pf:.2f}")
    print(f"  Win exits: {sum(1 for t in trades if t['type']=='WIN')} | SL exits: {sum(1 for t in trades if t['type']=='SL')}")

# Now test SHORT side: 3-bar rally → SHORT
print(f"\n{'=' * 95}")
print("SHORT SIDE: 3-bar rally → fade")
print("=" * 95)

for rise_pct, sl_pct, tgt_pct, max_hold, label in [
    (0.5, 0.010, 0.015, 15, "SHORT: rise=+0.5% SL=1.0% Tgt=1.5%"),
    (0.7, 0.010, 0.015, 15, "SHORT: rise=+0.7% SL=1.0% Tgt=1.5%"),
    (1.0, 0.010, 0.015, 15, "SHORT: rise=+1.0% SL=1.0% Tgt=1.5%"),
]:
    trades = []
    for sym, candles in data.items():
        days = get_day_candles(candles)
        for day, dc in sorted(days.items()):
            if len(dc) < 60: continue
            entered = False
            for i in range(10, len(dc) - 2):
                if entered: break
                rise_start = dc[i-2]['open']
                rise_end = dc[i]['close']
                if rise_start <= 0: continue
                rise = (rise_end - rise_start) / rise_start * 100
                
                if rise >= rise_pct and dc[i+1]['close'] < dc[i]['close']:
                    entry = dc[i+1]['close']
                    sl = entry * (1 + sl_pct)
                    target = entry * (1 - tgt_pct)
                    for j in range(i+2, min(i+max_hold, len(dc))):
                        if dc[j]['high'] >= sl:
                            trades.append({'pnl': (entry-sl)/entry*CAPITAL-BROKERAGE, 'type': 'SL'}); entered=True; break
                        elif dc[j]['low'] <= target:
                            trades.append({'pnl': (entry-target)/entry*CAPITAL-BROKERAGE, 'type': 'WIN'}); entered=True; break
                entered = False
    
    if not trades: 
        print(f"\n{label}\n  No trades"); continue
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    print(f"\n{label}")
    print(f"  Trades: {len(trades)} | Wins: {len(wins)} | WR: {wr:.1f}% | Net: ₹{total:,.0f} | PF: {pf:.2f}")

# Combined LONG + SHORT
print(f"\n{'=' * 95}")
print("COMBINED LONG + SHORT: Best params")
print("=" * 95)

# Use drop=-1.0% LONG + rise=+1.0% SHORT
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        entered = False
        for i in range(10, len(dc) - 2):
            if entered: break
            start = dc[i-2]['open']
            end = dc[i]['close']
            if start <= 0: continue
            move = (end - start) / start * 100
            
            # LONG: 3-bar drop >=1%, reclaim
            if move <= -1.0 and dc[i+1]['close'] > dc[i]['close']:
                entry = dc[i+1]['close']
                sl = entry * 0.99
                target = entry * 1.015
                for j in range(i+2, min(i+15, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE, 'dir': 'L'}); entered=True; break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE, 'dir': 'L'}); entered=True; break
            # SHORT: 3-bar rally >=1%, fade
            elif move >= 1.0 and dc[i+1]['close'] < dc[i]['close']:
                entry = dc[i+1]['close']
                sl = entry * 1.01
                target = entry * 0.985
                for j in range(i+2, min(i+15, len(dc))):
                    if dc[j]['high'] >= sl:
                        trades.append({'pnl': (entry-sl)/entry*CAPITAL-BROKERAGE, 'dir': 'S'}); entered=True; break
                    elif dc[j]['low'] <= target:
                        trades.append({'pnl': (entry-target)/entry*CAPITAL-BROKERAGE, 'dir': 'S'}); entered=True; break
            entered = False

if trades:
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    longs = [t for t in trades if t['dir'] == 'L']
    shorts = [t for t in trades if t['dir'] == 'S']
    print(f"\nCOMBINED (LONG + SHORT)")
    print(f"  Total: {len(trades)} | L:{len(longs)} S:{len(shorts)} | WR: {wr:.1f}% | Net: ₹{total:,.0f} | PF: {pf:.2f}")
    print(f"  LONG: {len([t for t in longs if t['pnl']>0])}/{len(longs)} wins, ₹{sum(t['pnl'] for t in longs):,.0f}")
    print(f"  SHORT: {len([t for t in shorts if t['pnl']>0])}/{len(shorts)} wins, ₹{sum(t['pnl'] for t in shorts):,.0f}")

conn.close()
